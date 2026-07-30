package zw.co.innbucks.middleware.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestContainer.class)
class ObservabilityWiringTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ObservationRegistry observationRegistry;

    @Test
    void coreObservabilityBeansArePresent() {
        assertThat(meterRegistry).isNotNull();
        assertThat(observationRegistry).isNotNull();
    }

    @Test
    void applicationAndProfileTagsAreAppliedToMetrics() {
        meterRegistry.counter("middleware.startup.marker").increment();

        assertThat(meterRegistry.find("middleware.startup.marker")
                .tag("application", "innbucks-middleware")
                .tag("profile", "test")
                .counter())
                .isNotNull();
    }
}
