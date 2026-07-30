package zw.co.innbucks.middleware.register;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public registration: local customer row first, then the core-banking saga
 * (create client → open + activate the wallet), then the core mapping on the
 * row. Deliberately NO enclosing DB transaction — the core calls are slow
 * remote I/O and must never pin a connection (the proven Hikari-exhaustion
 * lesson), and every step is individually resumable instead:
 *
 * <ul>
 *   <li>The whole operation runs under the MSISDN-namespaced Idempotency-Key
 *       (claim-row: concurrent duplicates can't double-run).</li>
 *   <li>A crash mid-saga leaves the row WITHOUT core_external_id; the next
 *       attempt for the same MSISDN picks that row up and re-drives the saga
 *       (the adapter resumes each leg by positive state verification).</li>
 *   <li>A fully registered MSISDN (core mapping present) → 409.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterService {

    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final MsisdnNormalizerRegistry msisdnRegistry;
    private final CountryProperties countryProperties;
    private final CoreBankingPort corePort;
    private final NationalIdHasher nationalIdHasher;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final Clock clock;

    private static final String INSERT_CUSTOMER_SQL = """
            INSERT INTO customer
                (id, country, msisdn, pin_hash, kyc_tier, national_id_hash, core_provider,
                 status, failed_pin_attempts, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?)
            """;

    private static final String SET_CORE_MAPPING_SQL = """
            UPDATE customer SET core_external_id = ?, updated_at = ? WHERE id = ?
            """;

    public IdempotencyService.Result<RegisterResponse> register(String rawIdempotencyKey,
                                                                RegisterRequest request) {
        String msisdn = msisdnRegistry.forDeployment().normalize(request.msisdn());
        // Pre-auth there is no customer scope yet — the normalized MSISDN is
        // the namespace, so two subscribers can never collide on a raw key.
        String key = IdempotencyKeys.namespaced(msisdn, rawIdempotencyKey);
        return idempotencyService.execute(key, "POST", "/register", request,
                RegisterResponse.class, 201, () -> doRegister(key, msisdn, request));
    }

    private RegisterResponse doRegister(String namespacedKey, String msisdn, RegisterRequest request) {
        String country = countryProperties.country().name();
        UUID customerId = resolveCustomerRow(country, msisdn, request);
        String walletExternalId = customerId + ":wallet";

        try {
            // Adapter legs are individually idempotent + resumable; keys are
            // derived per step so a crash can't replay the wrong leg.
            CoreCustomerRef ref = corePort.createCustomer(
                    new CreateCustomerCommand(customerId.toString(), msisdn,
                            request.firstName().trim(), request.lastName().trim(), Map.of()),
                    new IdempotencyKey(namespacedKey.substring(0, 56) + ":client"));
            AccountRef wallet = corePort.openDepositAccount(ref, walletExternalId,
                    new IdempotencyKey(namespacedKey.substring(0, 56) + ":wallet"));

            jdbcTemplate.update(SET_CORE_MAPPING_SQL,
                    ref.externalId(), Timestamp.from(clock.instant()), customerId);
            auditService.record(AuditAction.REGISTER_SUCCESS, AuditOutcome.SUCCESS, customerId, null,
                    Map.of("msisdn", MsisdnMasking.mask(msisdn), "wallet", wallet.externalId()));
            log.info("Registered customer id={} msisdn={} wallet={}",
                    customerId, MsisdnMasking.mask(msisdn), wallet.externalId());
            return new RegisterResponse(customerId,
                    CustomerStatus.PENDING_VERIFICATION.dbValue(), wallet.externalId());
        } catch (RuntimeException ex) {
            // The local row stays WITHOUT a core mapping — the next attempt for
            // this MSISDN resumes the saga instead of failing on the unique key.
            auditService.record(AuditAction.REGISTER_FAILURE, AuditOutcome.FAILURE, customerId, null,
                    Map.of("msisdn", MsisdnMasking.mask(msisdn), "reason", safeReason(ex)));
            throw ex;
        }
    }

    /**
     * New MSISDN → fresh row. Known MSISDN without a core mapping → a crashed
     * earlier registration; reuse the row and resume. Known MSISDN WITH a
     * mapping → already registered (409).
     */
    private UUID resolveCustomerRow(String country, String msisdn, RegisterRequest request) {
        Optional<Customer> existing = customerRepository.findByCountryAndMsisdn(country, msisdn);
        if (existing.isPresent()) {
            Customer customer = existing.get();
            if (customer.getCoreExternalId() != null) {
                throw new CustomerAlreadyExistsException(MsisdnMasking.mask(msisdn));
            }
            log.info("Registration resume: incomplete customer row id={} msisdn={}",
                    customer.getId(), MsisdnMasking.mask(msisdn));
            return customer.getId();
        }
        UUID customerId = UUID.randomUUID();
        Instant now = clock.instant();
        // INSERT via JdbcTemplate — client-assigned @Id, the save()-emits-UPDATE gotcha.
        jdbcTemplate.update(INSERT_CUSTOMER_SQL,
                customerId,
                country,
                msisdn,
                KycTier.BASIC.dbValue(),
                request.nationalId() == null || request.nationalId().isBlank()
                        ? null : nationalIdHasher.hash(request.nationalId().trim()),
                corePort.provider().name(),
                CustomerStatus.PENDING_VERIFICATION.dbValue(),
                Timestamp.from(now),
                Timestamp.from(now));
        return customerId;
    }

    private static String safeReason(Throwable ex) {
        String msg = ex.getMessage();
        String reason = msg == null ? ex.getClass().getSimpleName() : msg;
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
