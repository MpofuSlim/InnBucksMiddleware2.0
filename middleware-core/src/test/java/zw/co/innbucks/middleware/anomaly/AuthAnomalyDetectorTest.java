package zw.co.innbucks.middleware.anomaly;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contract: distinct ACCOUNTS per source drive everything, one source's
 * behaviour never affects another's, and nothing in here can break a sign-in.
 */
class AuthAnomalyDetectorTest {

    private static final String ATTACKER = "203.0.113.7";
    private static final String OFFICE = "41.173.193.211";

    private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-03T12:00:00Z"));

    private AuthAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        when(ipResolver.resolve(any())).thenReturn(ATTACKER);
        detector = build(properties(true, true, 3, 5));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuthAnomalyDetector build(AuthAnomalyProperties properties) {
        return new AuthAnomalyDetector(properties, ipResolver, auditService, meterRegistry, clock);
    }

    private static AuthAnomalyProperties properties(boolean enabled, boolean blockEnabled,
                                                    int alertAt, int blockAt) {
        return new AuthAnomalyProperties(enabled, Duration.ofMinutes(15), alertAt, blockAt,
                Duration.ofMinutes(15), blockEnabled, 1000, 1000);
    }

    private void failAgainst(String... msisdns) {
        for (String msisdn : msisdns) {
            detector.recordFailure(AuthFailureKind.LOGIN, msisdn);
        }
    }

    @Test
    void manyFailuresAgainstOneAccountIsNotASpray() {
        // The whole point of counting accounts and not attempts: one customer
        // fat-fingering their PIN twenty times must never look like an attack.
        for (int i = 0; i < 20; i++) {
            detector.recordFailure(AuthFailureKind.LOGIN, "+263771234567");
        }

        assertThat(detector.isBlocked(ATTACKER)).isFalse();
        verify(auditService, never()).record(eq(AuditAction.CREDENTIAL_SPRAY_DETECTED), any(), any(), any(), anyMap());
    }

    @Test
    void failuresAcrossDistinctAccountsAlertAtTheThreshold() {
        failAgainst("+263771000001", "+263771000002");
        assertThat(counted("observed")).isZero();

        failAgainst("+263771000003");

        assertThat(counted("observed")).isEqualTo(1.0);
        verify(auditService).record(eq(AuditAction.CREDENTIAL_SPRAY_DETECTED), any(), eq(null), eq(null), anyMap());
        // Alerting is not blocking — that is a separate, higher threshold.
        assertThat(detector.isBlocked(ATTACKER)).isFalse();
    }

    @Test
    void crossingTheBlockThresholdBlocksTheSource() {
        failAgainst("+2637710001", "+2637710002", "+2637710003", "+2637710004", "+2637710005");

        assertThat(detector.isBlocked(ATTACKER)).isTrue();
        assertThat(detector.remainingBlockSeconds(ATTACKER)).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(counted("blocked")).isEqualTo(1.0);
    }

    @Test
    void aBlockedSourceDoesNotBlockEveryoneElse() {
        failAgainst("+2637710001", "+2637710002", "+2637710003", "+2637710004", "+2637710005");

        assertThat(detector.isBlocked(OFFICE)).isFalse();
    }

    @Test
    void theBlockLiftsWhenItExpires() {
        failAgainst("+2637710001", "+2637710002", "+2637710003", "+2637710004", "+2637710005");
        assertThat(detector.isBlocked(ATTACKER)).isTrue();

        clock.advance(Duration.ofMinutes(16));

        assertThat(detector.isBlocked(ATTACKER)).isFalse();
        assertThat(detector.remainingBlockSeconds(ATTACKER)).isZero();
    }

    @Test
    void aQuietWindowResetsTheCount() {
        failAgainst("+2637710001", "+2637710002");
        clock.advance(Duration.ofMinutes(16));

        failAgainst("+2637710003");

        // Would have been the third distinct account — but the window rolled,
        // so this is the first of a fresh one and nothing fires.
        assertThat(counted("observed")).isZero();
    }

    @Test
    void repeatedFailuresAgainstTheSameAccountsAlertOncePerWindow() {
        failAgainst("+2637710001", "+2637710002", "+2637710003");
        failAgainst("+2637710001", "+2637710002", "+2637710003");

        assertThat(counted("observed")).isEqualTo(1.0);
        verify(auditService, times(1))
                .record(eq(AuditAction.CREDENTIAL_SPRAY_DETECTED), any(), any(), any(), anyMap());
    }

    @Test
    void observeOnlyModeAlertsButNeverBlocks() {
        AuthAnomalyDetector observeOnly = build(properties(true, false, 3, 5));

        for (int i = 1; i <= 10; i++) {
            observeOnly.recordFailure(AuthFailureKind.LOGIN, "+26377100" + i);
        }

        assertThat(observeOnly.isBlocked(ATTACKER)).isFalse();
        assertThat(counted("observed")).isEqualTo(1.0);
        assertThat(counted("blocked")).isZero();
    }

    @Test
    void disabledMeansNoTrackingButStillCountsFailures() {
        AuthAnomalyDetector off = build(properties(false, true, 3, 5));

        for (int i = 1; i <= 10; i++) {
            off.recordFailure(AuthFailureKind.LOGIN, "+26377100" + i);
        }

        assertThat(off.isBlocked(ATTACKER)).isFalse();
        // The raw failure counter is the spike signal for Prometheus and is
        // emitted regardless of whether tracking is on.
        assertThat(meterRegistry.counter("innbucks.auth.failures", "kind", "login").count())
                .isEqualTo(10.0);
    }

    @Test
    void otpFailuresAreTrackedUnderTheirOwnKind() {
        detector.recordFailure(AuthFailureKind.OTP_VERIFY, "+2637710001");
        detector.recordFailure(AuthFailureKind.OTP_VERIFY, "+2637710002");
        detector.recordFailure(AuthFailureKind.OTP_VERIFY, "+2637710003");

        assertThat(meterRegistry.counter("innbucks.auth.failures", "kind", "otp_verify").count())
                .isEqualTo(3.0);
        assertThat(meterRegistry.counter("innbucks.auth.spray.detected",
                "kind", "otp_verify", "action", "observed").count()).isEqualTo(1.0);
    }

    /**
     * The invariant the whole design rests on. This runs on the sign-in path;
     * an exception escaping it turns a monitoring fault into a total auth
     * outage — strictly worse than the attack it watches for.
     */
    @Test
    void nothingEscapesEvenWhenItsCollaboratorsFail() {
        when(ipResolver.resolve(any())).thenThrow(new IllegalStateException("resolver down"));

        assertThatCode(() -> detector.recordFailure(AuthFailureKind.LOGIN, "+2637710001"))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailingAuditWriteStillLeavesTheSourceBlocked() {
        doThrow(new IllegalStateException("audit chain locked"))
                .when(auditService).record(any(), any(), any(), any(), anyMap());

        failAgainst("+2637710001", "+2637710002", "+2637710003", "+2637710004", "+2637710005");

        assertThat(detector.isBlocked(ATTACKER)).isTrue();
    }

    @Test
    void outsideARequestThereIsNoSourceToTrack() {
        RequestContextHolder.resetRequestAttributes();

        assertThatCode(() -> detector.recordFailure(AuthFailureKind.LOGIN, "+2637710001"))
                .doesNotThrowAnyException();
        assertThat(detector.isBlocked(ATTACKER)).isFalse();
    }

    @Test
    void aBlankSubjectIsIgnored() {
        detector.recordFailure(AuthFailureKind.LOGIN, null);
        detector.recordFailure(AuthFailureKind.LOGIN, "  ");

        assertThat(counted("observed")).isZero();
    }

    private double counted(String action) {
        return meterRegistry.counter("innbucks.auth.spray.detected",
                "kind", "login", "action", action).count();
    }

    /** A clock the test can move, so window and block expiry are testable without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
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
}
