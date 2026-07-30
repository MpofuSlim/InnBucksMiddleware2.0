package zw.co.innbucks.middleware.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.util.Set;

@RestController
@RequestMapping("/auth/dev")
@Profile("dev")
@Tag(name = "auth-dev", description = "DEV-ONLY helpers. Not registered outside the dev profile.")
@SecurityRequirements({})
public class DevTokenController {

    private final JwtIssuer issuer;
    private final Country deploymentCountry;

    public DevTokenController(JwtIssuer issuer, CountryProperties countryProperties) {
        this.issuer = issuer;
        this.deploymentCountry = countryProperties.country();
    }

    @PostMapping("/token")
    @Operation(
            summary = "DEV ONLY — mint a JWT with arbitrary claims",
            description = "Convenience for mobile / partner integration before the real login flow is available in a given environment. NOT registered in uat/prod."
    )
    public TokenResponse mint(@Valid @RequestBody DevTokenRequest request) {
        // Default to the customer access-token scopes so dev/test of the gated
        // endpoints (e.g. /me/profile, /auth/me) works without spelling out scopes.
        Set<String> scopes = (request.scopes() == null) ? CustomerScopes.DEFAULT : request.scopes();
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                request.subject(),
                deploymentCountry,
                request.kycTier(),
                scopes,
                request.deviceHash(),
                request.nationalIdHash()
        ));
        return new TokenResponse(token, "Bearer");
    }

    @Schema(description = "Dev token mint request. All fields except subject are optional.")
    public record DevTokenRequest(
            @NotBlank @Schema(description = "JWT 'sub' claim. Usually the customer's UUID.", example = "00000000-0000-0000-0000-000000000001")
            String subject,

            @Schema(description = "KYC tier the dev wants baked into the token.", example = "standard")
            String kycTier,

            @Schema(description = "Optional scope strings; map to SCOPE_ authorities in Spring Security. Defaults to the customer access-token scopes (customer:read, customer:write) when omitted.", example = "[\"customer:read\", \"customer:write\"]")
            Set<String> scopes,

            @Schema(description = "Device hash claim. Used by slice 4 device binding when that ships.")
            String deviceHash,

            @Schema(description = "Hash of the customer's national ID. Useful for audit attribution.")
            String nationalIdHash
    ) {
    }

    @Schema(description = "Issued dev token.")
    public record TokenResponse(
            @Schema(description = "HS256 JWT. Pass as 'Authorization: Bearer <token>'.")
            String accessToken,

            @Schema(description = "Always 'Bearer'.", example = "Bearer")
            String tokenType
    ) {
    }
}
