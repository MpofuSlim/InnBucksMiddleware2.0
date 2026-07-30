package zw.co.innbucks.middleware.otp;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.time.Clock;
import java.util.Arrays;

@Slf4j
@Configuration
@EnableConfigurationProperties(OtpProperties.class)
@RequiredArgsConstructor
public class OtpConfig {

    private final OtpProperties otpProperties;
    private final Environment environment;

    /**
     * Fallback SmsSender. Yields to any real-provider bean (Africa's Talking,
     * Twilio, ...) as soon as one is registered.
     */
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender consoleSmsSender() {
        return new ConsoleSmsSender();
    }

    @Bean
    public VerificationTokenIssuer verificationTokenIssuer(AuthProperties authProperties,
                                                           CountryProperties countryProperties,
                                                           Clock authClock) {
        return new VerificationTokenIssuer(authProperties, otpProperties, countryProperties, authClock);
    }

    /**
     * Hard safety net: refuse to start in prod (or any non-dev profile) if a
     * fixed OTP code is set. Catches the "we shipped dev config to prod" class
     * of incident before customers can be hit by it.
     */
    @PostConstruct
    void enforceFixedCodeIsDevOnly() {
        String fixed = otpProperties.fixedCode();
        if (fixed == null || fixed.isBlank()) {
            return;
        }
        boolean devProfileActive = Arrays.asList(environment.getActiveProfiles()).contains("dev")
                || Arrays.asList(environment.getActiveProfiles()).contains("test");
        if (!devProfileActive) {
            throw new IllegalStateException(
                    "innbucks.otp.fixed-code is set but the active profile(s) "
                            + Arrays.toString(environment.getActiveProfiles())
                            + " are not 'dev' or 'test'. Refusing to start — a fixed OTP code in any "
                            + "other environment is a security incident waiting to happen.");
        }
        log.warn("════════════════════════════════════════════════════════════════════");
        log.warn(" ⚠️  OTP FIXED-CODE IS ENABLED. Every code is '{}'.", fixed);
        log.warn("     This must NEVER reach uat / prod. Active profiles: {}",
                Arrays.toString(environment.getActiveProfiles()));
        log.warn("════════════════════════════════════════════════════════════════════");
    }
}
