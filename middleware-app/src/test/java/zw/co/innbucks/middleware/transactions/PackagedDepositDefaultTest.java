package zw.co.innbucks.middleware.transactions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the deposit switch's DEPLOYED posture: what the packaged configuration
 * resolves to on a cell that never sets {@code TRANSACTIONS_DEPOSIT_ENABLED}.
 * Off is the security property the switch exists for, so a packaged default
 * that drifts to on must fail the build, not wait for an operator to notice a
 * WARN banner.
 *
 * <p>Reads {@code src/main/resources} from DISK, deliberately. On the test
 * classpath, {@code src/test/resources/application.yaml} replaces the main
 * file and turns deposits ON for the flow ITs, so no Spring test in this
 * module ever sees the main default; a context-based test here would read the
 * test file and stay green whatever the main one said.
 *
 * <p>The environment is a {@link MockEnvironment}, which carries no system
 * environment or system properties: a developer who exported the staging
 * value for local work cannot make this pass or fail. Profile files are
 * layered the way Boot layers them, above the default file, and the binding is
 * the real {@link Binder} over the real {@link DepositProperties} record, so
 * the {@code ${TRANSACTIONS_DEPOSIT_ENABLED:false}} placeholder is resolved
 * exactly as at boot.
 */
class PackagedDepositDefaultTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Pattern PROFILE_FILE = Pattern.compile("application-(.+)\\.ya?ml");

    /** What a cell sees: the env var (if any), then the profile files, then the default file. */
    private static boolean depositsEnabled(Map<String, Object> envVars, String... profiles) throws IOException {
        MockEnvironment env = new MockEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));
        for (String profile : profiles) {
            load(yamlFile("application-" + profile)).forEach(sources::addLast);
        }
        load(yamlFile("application")).forEach(sources::addLast);
        return Binder.get(env)
                .bindOrCreate("innbucks.transactions.deposit", Bindable.of(DepositProperties.class))
                .enabled();
    }

    /** Boot reads either extension; so does this test. */
    private static Path yamlFile(String baseName) {
        Path yml = RESOURCES.resolve(baseName + ".yml");
        return Files.exists(yml) ? yml : RESOURCES.resolve(baseName + ".yaml");
    }

    private static List<PropertySource<?>> load(Path path) throws IOException {
        // A missing file is a failure, never a vacuous "nothing set, so off".
        assertThat(path).as("packaged %s", path).isRegularFile();
        return new YamlPropertySourceLoader().load(path.getFileName().toString(), new FileSystemResource(path));
    }

    /** Every packaged profile file, so a new one (a staging.yaml, say) is checked without editing this test. */
    static Stream<String> packagedProfiles() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            return files.map(f -> PROFILE_FILE.matcher(f.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    static Stream<String> packagedProfilesButDev() throws IOException {
        return packagedProfiles().filter(p -> !p.equals("dev"));
    }

    @Test
    void thePackagedDefaultIsOff() throws IOException {
        assertThat(depositsEnabled(Map.of())).isFalse();
    }

    /**
     * prod is what docker-compose runs when SPRING_PROFILES_ACTIVE is unset,
     * staging included; uat is the other deployment profile. Neither may turn
     * deposits on: staging opts in with the variable, which production never
     * carries.
     */
    @ParameterizedTest(name = "[{0}] layered on the default is off")
    @MethodSource("packagedProfilesButDev")
    void noPackagedDeploymentProfileTurnsItOn(String profile) throws IOException {
        assertThat(depositsEnabled(Map.of(), profile)).as("deposits under packaged profile %s", profile).isFalse();
    }

    @Test
    void theDeploymentProfilesAreAmongThoseChecked() throws IOException {
        // Guards the enumeration itself: if prod or uat stopped being a YAML
        // file here (moved, renamed, turned into .properties), the
        // parameterised test above would quietly stop checking it.
        assertThat(packagedProfiles()).contains("prod", "uat", "dev");
    }

    @Test
    void devIsThePackagedExceptionAndTheLayeringReallyReadsIt() throws IOException {
        // Also proves the profile layer is read at all: if it were silently
        // skipped, every "off" above would be off for the wrong reason.
        assertThat(depositsEnabled(Map.of(), "dev")).isTrue();
        assertThat(depositsEnabled(Map.of("TRANSACTIONS_DEPOSIT_ENABLED", "false"), "dev")).isFalse();
    }

    @Test
    void theVariableTheRunbookGivesStagingTurnsItOn() throws IOException {
        // The name in .env.example and the runbook must be the one the packaged
        // placeholder reads, or staging silently loses deposits on a roll.
        assertThat(depositsEnabled(Map.of("TRANSACTIONS_DEPOSIT_ENABLED", "true"), "prod")).isTrue();
        assertThat(depositsEnabled(Map.of("TRANSACTIONS_DEPOSIT_ENABLED", "true"))).isTrue();
    }
}
