package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    /**
     * Spring Boot 4 does not auto-publish an ObjectMapper bean for our
     * dependency set (jackson-databind is on the classpath via spring-web,
     * but no JacksonAutoConfiguration bean is registered). Provide one
     * explicitly so IdempotencyService (and anything else that needs
     * application-grade JSON marshalling) can autowire it.
     *
     * findAndRegisterModules() picks up jackson-datatype-jsr310 which is
     * required for LocalDate / Instant serialisation in our DTOs.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                // Without this mixin a ProblemDetail's custom properties
                // serialise NESTED under a "properties" object instead of being
                // flattened onto the body. Spring MVC's own converters register
                // it, so every error thrown from a controller already puts
                // errorCode at the top level — but AuthRateLimitFilter writes
                // its 429 with THIS mapper, straight to the response, and so
                // produced {"properties":{"errorCode":"rate_limited"}}. Clients
                // are told to branch on a top-level errorCode; rate-limit
                // responses were the one place that was not true.
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    }
}
