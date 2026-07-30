package zw.co.innbucks.middleware.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.auth.jwt.InnbucksPrincipal;

@RestController
@RequestMapping("/auth")
@Tag(name = "auth", description = "Authentication and identity-introspection endpoints.")
public class AuthController {

    @GetMapping("/me")
    @Operation(
            summary = "Return the authenticated principal",
            description = "Smoke-test / identity-introspection endpoint. Returns the typed claims decoded from the Bearer JWT."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decoded principal returned",
                    content = @Content(schema = @Schema(implementation = InnbucksPrincipal.class),
                            examples = @ExampleObject(name = "Authenticated", value = """
                                    {
                                      "subject": "550e8400-e29b-41d4-a716-446655440000",
                                      "country": "KE",
                                      "kycTier": "TIER_2",
                                      "scopes": ["customer:read", "customer:write"],
                                      "deviceHash": "d41d8cd98f00b204e9800998ecf8427e",
                                      "nationalIdHash": "5d41402abc4b2a76b9719d911017c592",
                                      "authTime": "2026-05-21T08:30:00Z",
                                      "issuedAt": "2026-05-21T08:30:00Z",
                                      "expiresAt": "2026-05-21T08:40:00Z"
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "No bearer token", value = """
                                    {
                                      "type": "about:blank",
                                      "title": "Unauthorized",
                                      "status": 401
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Bearer token lacks the required scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Missing scope", value = """
                                    {
                                      "type": "about:blank",
                                      "title": "Forbidden",
                                      "status": 403
                                    }
                                    """)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public InnbucksPrincipal me(@AuthenticationPrincipal Jwt jwt) {
        return InnbucksPrincipal.fromJwt(jwt);
    }
}
