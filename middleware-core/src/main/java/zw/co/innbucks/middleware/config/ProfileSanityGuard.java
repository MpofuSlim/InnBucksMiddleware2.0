package zw.co.innbucks.middleware.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Fail-fast guard against a contradictory profile set. A deployed environment
 * runs the {@code prod} profile, which must never carry the {@code dev} or
 * {@code test} conveniences — the dev token-mint endpoint is
 * {@code @Profile("dev")}, and {@code test} wires test secrets / seed data. If
 * {@code prod} is active alongside {@code dev}/{@code test}, refuse to start
 * rather than silently expose those in a deployment.
 *
 * <p>Single-profile runs are all fine: {@code prod} (deployments), {@code dev}
 * (local IDE), {@code test} (CI). This is intentionally narrow — it does not
 * try to detect "am I in production?" (there's no reliable signal); it only
 * rejects the clearly-contradictory combination.
 */
@Slf4j
@Component
public class ProfileSanityGuard {

    private final Environment environment;

    public ProfileSanityGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyProfilesAreNotContradictory() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("prod") && (active.contains("dev") || active.contains("test"))) {
            throw new IllegalStateException(
                    "Contradictory Spring profiles active: " + active + ". The 'prod' profile must "
                    + "not run alongside 'dev'/'test' — that would expose dev/test conveniences (e.g. "
                    + "the @Profile(\"dev\") token-mint endpoint) in a deployed environment. Set "
                    + "SPRING_PROFILES_ACTIVE to exactly one of: prod (deployments), dev (local), test (CI).");
        }
        log.debug("Profile sanity check passed (active profiles: {})", active);
    }
}
