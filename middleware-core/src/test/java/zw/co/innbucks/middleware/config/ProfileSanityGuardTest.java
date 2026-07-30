package zw.co.innbucks.middleware.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileSanityGuardTest {

    private static ProfileSanityGuard guard(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new ProfileSanityGuard(env);
    }

    @Test
    void failsWhenProdRunsWithDev() {
        assertThatThrownBy(() -> guard("prod", "dev").verifyProfilesAreNotContradictory())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory Spring profiles");
    }

    @Test
    void failsWhenProdRunsWithTest() {
        assertThatThrownBy(() -> guard("prod", "test").verifyProfilesAreNotContradictory())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsProdAlone() {
        assertThatCode(() -> guard("prod").verifyProfilesAreNotContradictory())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsDevAlone() {
        assertThatCode(() -> guard("dev").verifyProfilesAreNotContradictory())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsTestAlone() {
        assertThatCode(() -> guard("test").verifyProfilesAreNotContradictory())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNoActiveProfiles() {
        assertThatCode(() -> guard().verifyProfilesAreNotContradictory())
                .doesNotThrowAnyException();
    }
}
