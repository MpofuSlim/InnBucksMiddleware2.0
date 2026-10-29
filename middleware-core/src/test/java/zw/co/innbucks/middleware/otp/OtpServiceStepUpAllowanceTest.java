package zw.co.innbucks.middleware.otp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.anomaly.AuthAnomalyDetector;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.common.msisdn.ZimbabweMsisdnNormalizer;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The step-up SMS allowance is the signed-in customer's own. Before, it shared
 * the per-NUMBER allowance with the public {@code /auth/otp/request}, so anyone
 * who knew a number could spend it on PIN-reset requests and block every
 * payment at or above the owner's step-up threshold.
 */
class OtpServiceStepUpAllowanceTest {

    private static final String NUMBER = "+263771234567";
    private static final UUID OWNER = UUID.randomUUID();

    private final SmsSender sms = mock(SmsSender.class);
    private OtpService otp;

    @BeforeEach
    void setUp() {
        // Public per-number allowance 3 / 15 min; step-up per-customer 2 / 15 min.
        Limit generous = new Limit(1000, Duration.ofMinutes(1));
        RateLimitProperties limits = new RateLimitProperties(true, false, 0,
                generous, generous, generous, generous, generous, generous,
                new Limit(3, Duration.ofMinutes(15)),
                generous,
                new Limit(2, Duration.ofMinutes(15)),
                generous);
        otp = new OtpService(
                mock(JdbcTemplate.class),
                new OtpHasher("test-otp-hmac-secret-that-is-at-least-32-bytes-long"),
                new OtpProperties(Duration.ofMinutes(5), 5, Duration.ofMinutes(5), ""),
                new MsisdnNormalizerRegistry(List.of(new ZimbabweMsisdnNormalizer()),
                        new CountryProperties(Country.ZW)),
                new CountryProperties(Country.ZW),
                sms,
                mock(VerificationTokenIssuer.class),
                mock(AuditService.class),
                Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC),
                new RateLimiterService(limits),
                mock(AuthAnomalyDetector.class));
    }

    @Test
    void aStrangerExhaustingThePublicAllowanceCannotBlockTheOwnersApproval() {
        // Anyone who knows the number: three public PIN-reset requests, then refused.
        for (int i = 0; i < 3; i++) {
            otp.request("0771234567", OtpPurpose.PIN_RESET);
        }
        assertThatThrownBy(() -> otp.request("0771234567", OtpPurpose.PIN_RESET))
                .isInstanceOf(RateLimitExceededException.class);

        // The signed-in owner still gets their approval code.
        assertThatCode(() -> otp.requestStepUp(OWNER, NUMBER)).doesNotThrowAnyException();
        verify(sms).send(eq(NUMBER), contains("transaction approval"));
    }

    @Test
    void stepUpHasItsOwnCapStillAnAntiCostLimit() {
        otp.requestStepUp(OWNER, NUMBER);
        otp.requestStepUp(OWNER, NUMBER);

        assertThatThrownBy(() -> otp.requestStepUp(OWNER, NUMBER))
                .isInstanceOf(RateLimitExceededException.class);
        // Refused BEFORE the send: two codes went out, not three.
        verify(sms, times(2)).send(eq(NUMBER), anyString());
        // ...and spending it never touched the public allowance.
        assertThatCode(() -> otp.request("0771234567", OtpPurpose.PIN_RESET)).doesNotThrowAnyException();
    }

    @Test
    void eachCustomerHasTheirOwnStepUpAllowance() {
        otp.requestStepUp(OWNER, NUMBER);
        otp.requestStepUp(OWNER, NUMBER);
        assertThatCode(() -> otp.requestStepUp(UUID.randomUUID(), "+263771111111")).doesNotThrowAnyException();
    }

    @Test
    void stepUpCodesCannotBeIssuedThroughThePublicPath() {
        assertThatThrownBy(() -> otp.request("0771234567", OtpPurpose.STEP_UP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
