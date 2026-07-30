package zw.co.innbucks.middleware.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Pins the fail-closed contract: "deployment" = an active-profile set with NO
 * dev/test/it/local profile, INCLUDING the empty set. A prod container
 * launched without SPRING_PROFILES_ACTIVE must not boot on placeholders.
 */
class ProductionSecretsGuardTest {

    private static final String GOOD = "a-strong-real-secret-value-32-bytes-min!!";

    private MockEnvironment envWithAllSecrets() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("innbucks.auth.signing-key", GOOD + "-signing");
        env.setProperty("innbucks.auth.verification-signing-key", GOOD + "-verification");
        env.setProperty("innbucks.auth.national-id-hmac-key", GOOD + "-nid");
        env.setProperty("innbucks.otp.hmac-secret", GOOD + "-otp");
        env.setProperty("innbucks.audit.hmac-secret", GOOD + "-audit");
        env.setProperty("spring.datasource.password", "db-password");
        return env;
    }

    private void run(MockEnvironment env, String... profiles) {
        env.setActiveProfiles(profiles);
        new ProductionSecretsGuard(env).verifySecrets();
    }

    @Test
    void passesUnderDeploymentProfileWithRealSecrets() {
        assertThatCode(() -> run(envWithAllSecrets(), "prod")).doesNotThrowAnyException();
    }

    @Test
    void emptyProfileSetCountsAsDeployment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("innbucks.auth.signing-key", "dev-only-placeholder-key-32-bytes-long!!");
        assertThatIllegalStateException()
                .isThrownBy(() -> run(env))
                .withMessageContaining("EMPTY profile set");
    }

    @Test
    void devProfilePermitsPlaceholders() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("innbucks.auth.signing-key", "dev-only-jwt-signing-key-that-is-32-bytes");
        env.setProperty("innbucks.auth.verification-signing-key", "dev-only-verification-key-that-is-32-bytes");
        assertThatCode(() -> run(env, "dev")).doesNotThrowAnyException();
    }

    @Test
    void deploymentRejectsMissingSecret() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("innbucks.otp.hmac-secret", "");
        assertThatIllegalStateException()
                .isThrownBy(() -> run(env, "prod"))
                .withMessageContaining("OTP_HMAC_SECRET");
    }

    @Test
    void deploymentRejectsPlaceholderMarker() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("innbucks.audit.hmac-secret", "change-me-please-but-long-enough-32-bytes!!");
        assertThatIllegalStateException()
                .isThrownBy(() -> run(env, "prod"))
                .withMessageContaining("AUDIT_HMAC_SECRET");
    }

    @Test
    void deploymentRejectsBlankDatasourcePassword() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("spring.datasource.password", "");
        assertThatIllegalStateException()
                .isThrownBy(() -> run(env, "prod"))
                .withMessageContaining("DATASOURCE_PASSWORD");
    }

    @Test
    void equalSigningKeysAreRejectedInEveryEnvironment() {
        // The key-separation invariant is not a deployment-only concern: equal
        // keys in dev would let dev-shaped config leak into prod unnoticed.
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("innbucks.auth.verification-signing-key",
                env.getProperty("innbucks.auth.signing-key"));
        assertThatIllegalStateException()
                .isThrownBy(() -> run(env, "dev"))
                .withMessageContaining("EQUAL");
    }

    @Test
    void mixedProfileWithTestIsNotADeployment() {
        MockEnvironment env = new MockEnvironment();
        assertThatCode(() -> run(env, "test", "extra")).doesNotThrowAnyException();
    }
}
