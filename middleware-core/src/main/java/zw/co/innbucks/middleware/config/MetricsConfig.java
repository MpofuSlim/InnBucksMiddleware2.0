package zw.co.innbucks.middleware.config;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterFilter commonMetricsTags(
            @Value("${spring.application.name}") String application,
            @Value("${spring.profiles.active:default}") String profile) {
        return MeterFilter.commonTags(Tags.of(
                "application", application,
                "profile", profile
        ));
    }
}
