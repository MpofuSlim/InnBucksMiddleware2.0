package zw.co.innbucks.middleware.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "innbucks.cors")
public record CorsProperties(

        @NotEmpty(message = "CORS allowed origins must be configured; set CORS_ALLOWED_ORIGINS env var (comma-separated)")
        List<String> allowedOrigins
) {
}
