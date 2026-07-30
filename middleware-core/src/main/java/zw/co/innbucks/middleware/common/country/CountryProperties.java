package zw.co.innbucks.middleware.common.country;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "innbucks")
public record CountryProperties(

        @NotNull
        Country country
) {
}
