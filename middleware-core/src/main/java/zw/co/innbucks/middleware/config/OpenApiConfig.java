package zw.co.innbucks.middleware.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI innbucksOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Innbucks Middleware API")
                        .version("0.0.1")
                        .description("""
                                Middleware between the InnBucks super-app and the deployment's core banking system (Fineract; Veengu later) behind the CoreBankingPort.
                                All non-public endpoints require a Bearer JWT issued by /auth/login.
                                """))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("HS256 JWT issued by /auth/login or /auth/dev/token (dev only).")))
                // Default — every operation is protected unless overridden by @SecurityRequirements({})
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
