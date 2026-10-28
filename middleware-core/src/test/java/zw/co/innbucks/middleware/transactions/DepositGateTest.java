package zw.co.innbucks.middleware.transactions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the deposit switch: off refuses (and counts), on passes, and switching
 * it on where real customers could be is loud at every boot. "Where real
 * customers could be" is the secrets guard's notion of a deployment, the
 * EMPTY profile set included.
 */
class DepositGateTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger gateLogger = (Logger) LoggerFactory.getLogger(DepositGate.class);
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        // Pinned, so a logging config that quietens the package cannot hide
        // the INFO lines these tests read.
        previousLevel = gateLogger.getLevel();
        gateLogger.setLevel(Level.INFO);
        logs.start();
        gateLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        gateLogger.detachAppender(logs);
        gateLogger.setLevel(previousLevel);
    }

    private DepositGate gate(boolean enabled, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new DepositGate(new DepositProperties(enabled), env, meterRegistry);
    }

    private double refusals() {
        return meterRegistry.get("innbucks.transactions.deposit.refused").tag("reason", "disabled")
                .counter().count();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void offRefusesAndCountsEachRefusal() {
        DepositGate gate = gate(false, "prod");

        assertThatThrownBy(() -> gate.requireEnabled(UUID.randomUUID()))
                .isInstanceOf(DepositDisabledException.class);
        assertThatThrownBy(() -> gate.requireEnabled(UUID.randomUUID()))
                .isInstanceOf(DepositDisabledException.class);

        assertThat(refusals()).isEqualTo(2.0);
    }

    @Test
    void onLetsTheDepositThroughAndCountsNothing() {
        DepositGate gate = gate(true, "uat");

        assertThatCode(() -> gate.requireEnabled(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(refusals()).isZero();
    }

    @Test
    void theRefusalCounterExistsFromBootAtZero() {
        // A switched-off cell nobody calls must read 0, not "no such series" —
        // the counter is how ops learns whether the app still calls the endpoint.
        gate(false, "prod");
        assertThat(refusals()).isZero();
    }

    @Test
    void onUnderADeploymentProfileWarnsAtBoot() {
        gate(true, "uat").announce();

        assertThat(warnings()).isNotEmpty();
        assertThat(warnings().stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(m -> m.contains("CUSTOMER DEPOSITS ARE ON") && m.contains("[uat]"))
                .anyMatch(m -> m.contains("UNFUNDED"))
                .anyMatch(m -> m.contains("TRANSACTIONS_DEPOSIT_ENABLED"));
    }

    @Test
    void onWithNoProfileAtAllStillWarns() {
        // The empty set is a deployment (a container started without
        // SPRING_PROFILES_ACTIVE) — exactly where a forgotten value lands.
        gate(true).announce();

        assertThat(warnings()).isNotEmpty();
    }

    @Test
    void onUnderDevDoesNotWarn() {
        gate(true, "dev").announce();

        assertThat(warnings()).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("Customer deposits: ON"));
    }

    /**
     * The fail-closed claim, through real Spring binding: a configuration that
     * never names the key yields a gate that refuses. The test suite's
     * application.yaml replaces the main one, so "nobody wrote the key" is a
     * real state, not a hypothetical.
     */
    @Test
    void aConfigurationThatNeverNamesTheKeyBindsOff() {
        new ApplicationContextRunner()
                .withBean(SimpleMeterRegistry.class)
                .withUserConfiguration(DepositGate.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DepositProperties.class).enabled()).isFalse();
                    assertThatThrownBy(() -> context.getBean(DepositGate.class).requireEnabled(UUID.randomUUID()))
                            .isInstanceOf(DepositDisabledException.class);
                });
    }

    @Test
    void theStagingValueBindsOn() {
        new ApplicationContextRunner()
                .withBean(SimpleMeterRegistry.class)
                .withUserConfiguration(DepositGate.class)
                .withPropertyValues("innbucks.transactions.deposit.enabled=true")
                .run(context -> assertThatCode(() ->
                        context.getBean(DepositGate.class).requireEnabled(UUID.randomUUID()))
                        .doesNotThrowAnyException());
    }

    @Test
    void offNeverWarnsAndSaysSoOnce() {
        gate(false, "prod").announce();

        assertThat(warnings()).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Customer deposits: OFF — POST /transactions/deposit answers 403 deposit_disabled");
    }
}
