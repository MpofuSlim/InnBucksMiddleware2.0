package zw.co.innbucks.middleware.idempotency;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "innbucks.idempotency")
public record IdempotencyProperties(

        @NotNull
        Duration ttl
) {
}
