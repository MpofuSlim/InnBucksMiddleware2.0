package zw.co.innbucks.middleware.auth.pin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.exception.PinSetNotAllowedException;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.otp.ConsumedVerificationTokenStore;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.VerificationTokenVerifier;
import zw.co.innbucks.middleware.otp.VerifiedToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A PIN change signs the customer out everywhere, inside the PIN change's own
 * transaction. The lock-level race against an in-flight rotation needs real
 * Postgres and lives in {@code PinResetSignOutIntegrationTest}; this pins the
 * wiring: the order of the writes, the audit record, and that a refused
 * change revokes nothing.
 */
class PinServiceTest {

    private static final String MSISDN = "+263771234567";
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    private final VerificationTokenVerifier verifier = mock(VerificationTokenVerifier.class);
    private final ConsumedVerificationTokenStore consumedStore = mock(ConsumedVerificationTokenStore.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final PinHasher pinHasher = mock(PinHasher.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final AuditService audit = mock(AuditService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final PinService service = new PinService(verifier, consumedStore, customers, pinHasher,
            refreshTokens, audit, new CountryProperties(Country.ZW), jdbc,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(pinHasher.hash("4321")).thenReturn("$argon2id$new");
        when(jdbc.update(contains("UPDATE customer"), any(Object[].class))).thenReturn(1);
    }

    private void tokenFor(OtpPurpose purpose, CustomerStatus status) {
        when(verifier.verify("vt", purpose)).thenReturn(
                new VerifiedToken(MSISDN, purpose, "jti-1", NOW.plusSeconds(300), null));
        Customer customer = new Customer();
        customer.setId(customerId);
        customer.setStatus(status.dbValue());
        when(customers.findByCountryAndMsisdn("ZW", MSISDN)).thenReturn(Optional.of(customer));
    }

    @Test
    void aResetRevokesEverySessionAfterThePinUpdateAndAuditsTheCount() {
        tokenFor(OtpPurpose.PIN_RESET, CustomerStatus.LOCKED);
        when(refreshTokens.revokeAllSessions(customerId, "pin_reset")).thenReturn(3);

        service.resetPin("vt", "4321");

        // Customer row first (it is the lock revokeAllSessions relies on),
        // then the revocation, then the audit row that records it.
        InOrder order = inOrder(jdbc, refreshTokens, audit);
        order.verify(jdbc).update(contains("UPDATE customer"), any(Object[].class));
        order.verify(refreshTokens).revokeAllSessions(customerId, "pin_reset");
        order.verify(audit).record(AuditAction.PIN_RESET, AuditOutcome.SUCCESS, customerId, null,
                Map.of("refreshTokensRevoked", 3));
    }

    @Test
    void aFirstPinSetRevokesToo_soNoSessionEverPredatesTheCurrentPin() {
        tokenFor(OtpPurpose.PIN_SETUP, CustomerStatus.PENDING_VERIFICATION);
        when(refreshTokens.revokeAllSessions(customerId, "pin_set")).thenReturn(0);

        service.setInitialPin("vt", "4321");

        verify(refreshTokens).revokeAllSessions(customerId, "pin_set");
        verify(audit).record(AuditAction.PIN_SET, AuditOutcome.SUCCESS, customerId, null,
                Map.of("refreshTokensRevoked", 0));
    }

    @Test
    void aRefusedPinChangeSignsNobodyOut() {
        // RESET on a customer who never set a PIN: the state machine refuses.
        tokenFor(OtpPurpose.PIN_RESET, CustomerStatus.PENDING_VERIFICATION);

        assertThatThrownBy(() -> service.resetPin("vt", "4321"))
                .isInstanceOf(PinSetNotAllowedException.class);

        verify(refreshTokens, never()).revokeAllSessions(any(), anyString());
        verify(jdbc, never()).update(contains("UPDATE customer"), any(Object[].class));
    }

    @Test
    void aFailedRevocationFailsTheWholePinChange() {
        // Propagates, so the @Transactional rolls the new hash back with it:
        // a PIN change that could not sign the old sessions out is not made.
        tokenFor(OtpPurpose.PIN_RESET, CustomerStatus.ACTIVE);
        when(refreshTokens.revokeAllSessions(customerId, "pin_reset"))
                .thenThrow(new QueryTimeoutException("lock wait"));

        assertThatThrownBy(() -> service.resetPin("vt", "4321"))
                .isInstanceOf(QueryTimeoutException.class);

        verify(audit, never()).record(eq(AuditAction.PIN_RESET), any(), any(), any(), any());
    }

    @Test
    void theRevocationJoinsThePinChangesTransaction() throws Exception {
        // Both PIN entry points own a transaction, and revokeAllSessions
        // refuses to run without one — the two halves of "same transaction".
        assertThat(PinService.class.getMethod("resetPin", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(PinService.class.getMethod("setInitialPin", String.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(RefreshTokenService.class.getMethod("revokeAllSessions", UUID.class, String.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
