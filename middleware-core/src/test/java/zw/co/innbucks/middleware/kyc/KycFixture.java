package zw.co.innbucks.middleware.kyc;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRecordKycTierResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.crypto.KycTestKeys;
import zw.co.innbucks.middleware.kyc.image.KycImageProcessor;
import zw.co.innbucks.middleware.kyc.image.TestImages;
import zw.co.innbucks.middleware.kyc.nationalid.KycNationalIds;
import zw.co.innbucks.middleware.kyc.nationalid.NationalIdValidatorRegistry;
import zw.co.innbucks.middleware.kyc.nationalid.ZimbabweNationalIdValidator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Everything KycService, KycDecisionApplier and KycLifecycleJob need, real
 * where it matters (cipher, image pipeline, national-ID rules, store
 * semantics) and mocked at the edges (audit, idempotency, rate limits,
 * customer repository). ZW cell. Synthetic IDs only.
 */
class KycFixture {

    static final Instant T0 = Instant.parse("2026-10-28T07:40:00Z");
    static final String ID_TYPED = "63-1234567 X 18";
    static final String ID_CANONICAL = "631234567X18";
    static final String ADDRESS = "12 Example Road, Harare";

    final MutableClock clock = new MutableClock(T0);
    final KycCipher cipher = KycTestKeys.cipher();
    final InMemoryKycStore store = new InMemoryKycStore(cipher);
    final NationalIdHasher hasher = new NationalIdHasher("test-national-id-hmac-key-at-least-32-bytes-long");
    final KycNationalIds nationalIds = new KycNationalIds(
            new NationalIdValidatorRegistry(List.of(new ZimbabweNationalIdValidator()),
                    new CountryProperties(Country.ZW)), hasher);
    final KycProperties properties = new KycProperties(true, "/run/secrets/innbucks-kyc-keys",
            Duration.ofDays(14), Duration.ofDays(30), null, 0, new KycProperties.Image(
            2 * 1024 * 1024, 16_000_000L, 200, 1600, 1080, 0.9f, 2, 4, Duration.ofSeconds(1)), null);
    final KycImageProcessor images = new KycImageProcessor(properties.image());
    final KycHousekeeping housekeeping = new KycHousekeeping(store, store);
    final KycRateLimiter rateLimiter = mock(KycRateLimiter.class);
    final AuditService audit = mock(AuditService.class);
    final IdempotencyService idempotency = mock(IdempotencyService.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final PlatformTransactionManager tx = new NoopTransactionManager();
    final Map<UUID, Customer> customers = new HashMap<>();
    final CustomerRepository customerRepository = mock(CustomerRepository.class);
    KycTierResolver tierResolver = new CustomerRecordKycTierResolver(meters);

    KycFixture() {
        when(customerRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(customers.get(inv.<UUID>getArgument(0))));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), eq(KycSubmissionCreated.class),
                anyInt(), any())).thenAnswer(inv -> {
            Supplier<?> work = inv.getArgument(6);
            return new IdempotencyService.Result<>(work.get(), inv.<Integer>getArgument(5), false);
        });
    }

    KycService service() {
        return new KycService(store, store, cipher, nationalIds, hasher, images, housekeeping, rateLimiter,
                customerRepository, tierResolver, idempotency, audit, properties, new CountryProperties(Country.ZW),
                tx, clock, meters);
    }

    KycDecisionApplier applier() {
        return new KycDecisionApplier(store, cipher, audit, properties, tx, clock);
    }

    KycLifecycleJob job() {
        return new KycLifecycleJob(store, housekeeping, audit, properties, tx, clock, meters);
    }

    /** A registered customer on {@code tier}; {@code signupIdTyped} as typed at sign-up, or null. */
    UUID customer(String tier, String signupIdTyped) {
        UUID id = UUID.randomUUID();
        String signupHash = signupIdTyped == null ? null : hasher.hash(signupIdTyped.trim());
        customers.put(id, new Customer(id, "ZW", "+26377" + (1000000 + customers.size()), "pin", tier, signupHash,
                "FINERACT", id.toString(), "active", 0, null, null, T0, T0));
        if (signupHash != null) {
            store.signupHashes.put(id, signupHash);
        }
        return id;
    }

    UUID open(KycService service, UUID customerId, String targetTier, String nationalId, String address) {
        CreateKycSubmissionRequest request = new CreateKycSubmissionRequest(targetTier, nationalId, address);
        return UUID.fromString(service.create(customerId, "198.51.100.7", UUID.randomUUID().toString(), request)
                .body().id());
    }

    KycFileStoredView upload(KycService service, UUID customerId, UUID submissionId, KycFileKind kind) {
        byte[] jpeg = TestImages.jpeg(800, 600);
        return service.upload(customerId, "198.51.100.7", submissionId, kind.code(), jpeg.length,
                new ByteArrayInputStream(jpeg));
    }

    /** Opens a standard request and uploads all three files. */
    UUID completeStandard(KycService service, UUID customerId) {
        UUID submission = open(service, customerId, "standard", ID_TYPED, null);
        for (KycFileKind kind : List.of(KycFileKind.ID_FRONT, KycFileKind.ID_BACK, KycFileKind.SELFIE)) {
            upload(service, customerId, submission, kind);
        }
        return submission;
    }

    /** A clock tests move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Runs every callback inline; the in-memory store has no transactions. */
    static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
