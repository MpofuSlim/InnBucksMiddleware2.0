package zw.co.innbucks.middleware.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed secrets guard. A "deployment" is any run whose active-profile
 * set contains NONE of {@code dev/test/it/local} — <b>including the empty
 * set</b>: a container launched without {@code SPRING_PROFILES_ACTIVE} must
 * not boot on placeholders. Local dev opts out explicitly by activating a
 * dev-ish profile; production can never opt out by forgetting one.
 *
 * <p>Under a deployment profile, every guarded secret must be present, at
 * least {@value #MIN_SECRET_LENGTH} characters after trimming, and free of
 * placeholder markers ({@code change-me}, {@code placeholder},
 * {@code dev-only}). Provision each with {@code openssl rand -base64 48}.
 *
 * <p>One check runs in EVERY environment, dev included: the access-token
 * signing key and the verification-token signing key must differ. Equal keys
 * mean whoever can mint access tokens can mint PIN-reset verification tokens
 * — an account-takeover primitive — so that is a misconfiguration everywhere,
 * not just in production.
 */
@Slf4j
@Component
public class ProductionSecretsGuard {

    static final int MIN_SECRET_LENGTH = 32;
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");
    private static final List<String> PLACEHOLDER_MARKERS = List.of("change-me", "placeholder", "dev-only");

    /** property key -> env var name (for the error message the operator actually reads). */
    private static final Map<String, String> GUARDED_SECRETS = new LinkedHashMap<>() {{
        put("innbucks.auth.signing-key", "JWT_SIGNING_KEY");
        put("innbucks.auth.verification-signing-key", "VERIFICATION_SIGNING_KEY");
        put("innbucks.auth.national-id-hmac-key", "NATIONAL_ID_HMAC_KEY");
        put("innbucks.otp.hmac-secret", "OTP_HMAC_SECRET");
        put("innbucks.audit.hmac-secret", "AUDIT_HMAC_SECRET");
    }};

    private final Environment environment;

    public ProductionSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifySecrets() {
        verifySigningKeysDiffer();

        List<String> active = Arrays.asList(environment.getActiveProfiles());
        boolean deployment = active.stream().noneMatch(p ->
                NON_DEPLOYMENT_PROFILES.contains(p.toLowerCase(Locale.ROOT)));
        if (!deployment) {
            log.debug("ProductionSecretsGuard: non-deployment profiles {} — placeholder secrets permitted", active);
            return;
        }

        List<String> failures = new java.util.ArrayList<>(GUARDED_SECRETS.entrySet().stream()
                .map(e -> check(e.getKey(), e.getValue()))
                .filter(java.util.Objects::nonNull)
                .toList());
        // DB password: a blank one means an unauthenticated Postgres — a tamper
        // surface, not a convenience. Length is the operator's call, so only
        // blank/placeholder is rejected (unlike the >=32-char crypto keys).
        String dbPassword = trimmed("spring.datasource.password");
        if (dbPassword.isEmpty()
                || PLACEHOLDER_MARKERS.stream().anyMatch(dbPassword.toLowerCase(Locale.ROOT)::contains)) {
            failures.add("spring.datasource.password (env DATASOURCE_PASSWORD) is blank or a placeholder");
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under deployment profiles " + active + " (an EMPTY profile set "
                    + "counts as deployment — activate 'dev'/'test'/'local' explicitly for local runs). "
                    + "Secret problems: " + String.join("; ", failures)
                    + ". Provision each with: openssl rand -base64 48");
        }
        log.info("ProductionSecretsGuard: all {} guarded secrets pass under profiles {}",
                GUARDED_SECRETS.size(), active);
    }

    private void verifySigningKeysDiffer() {
        String signing = trimmed("innbucks.auth.signing-key");
        String verification = trimmed("innbucks.auth.verification-signing-key");
        if (!signing.isEmpty() && signing.equals(verification)) {
            throw new IllegalStateException(
                    "innbucks.auth.signing-key and innbucks.auth.verification-signing-key are EQUAL. "
                    + "The verification-token key must be a separate secret — a shared key lets an "
                    + "access-token minter mint PIN-reset verification tokens (account takeover). "
                    + "Generate a distinct VERIFICATION_SIGNING_KEY.");
        }
    }

    private String check(String property, String envVar) {
        String value = trimmed(property);
        if (value.length() < MIN_SECRET_LENGTH) {
            return property + " (env " + envVar + ") is missing or shorter than " + MIN_SECRET_LENGTH + " chars";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains)) {
            return property + " (env " + envVar + ") still carries a placeholder value";
        }
        return null;
    }

    private String trimmed(String property) {
        String value = environment.getProperty(property, "");
        return value == null ? "" : value.trim();
    }
}
