package zw.co.innbucks.middleware.me.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.me.IdentityResponse;
import zw.co.innbucks.middleware.me.IdentityService;
import zw.co.innbucks.middleware.me.IdentityTokenResponse;

import java.util.UUID;

/**
 * Identity tokens for third parties (see {@link IdentityService}). Both
 * answers are {@code no-store}: one is a credential, the other personal data.
 */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "me", description = "Current-customer endpoints. Identity is taken from the Bearer JWT.")
public class IdentityController {

    private static final String FORBIDDEN_EXAMPLE = """
            {"type":"about:blank","title":"Forbidden","status":403,
             "detail":"Your access token doesn't grant permission for this action.",
             "errorCode":"insufficient_scope"}
            """;

    private final IdentityService identityService;

    @PostMapping("/identity-token")
    @Operation(summary = "Get a short-lived identity token to hand to a third party",
            description = "For a service the customer uses that needs to know who they are (the Magaya "
                    + "app's profile-picture function). The token's only scope is identity:read: it "
                    + "reads GET /me/identity and is refused everywhere else, so it can never make a "
                    + "payment or read a balance. Expires 2 minutes after issue (plus the usual 60 s "
                    + "clock-skew allowance) and can be used more than once until then; it cannot mint "
                    + "another. Send the app's normal access token here, and only the identity token to "
                    + "the third party.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Identity token issued",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdentityTokenResponse.class),
                            examples = @ExampleObject(value = """
                                    {"identityToken":"eyJhbGciOiJIUzI1NiJ9.eyJzY29wZXMiOlsiaWRlbnRpdHk6cmVhZCJdfQ.c2lnbmF0dXJl",
                                     "expiresIn":120}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The token is not the app's access token "
                    + "(an identity token cannot mint another)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = FORBIDDEN_EXAMPLE)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ResponseEntity<IdentityTokenResponse> identityToken(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identityService.issueToken(UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/identity")
    @Operation(summary = "Who the holder of an identity token is",
            description = "Called by the third party with the IDENTITY token as Bearer (never the app's "
                    + "access token, which is refused here). Answers the customer's id and phone number "
                    + "only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Identity returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IdentityResponse.class),
                            examples = @ExampleObject(value = """
                                    {"customerId":"3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f",
                                     "msisdn":"+263771234567"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired identity token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Not an identity token (the app's access "
                    + "token is refused here)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "No customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_identity:read')")
    public ResponseEntity<IdentityResponse> identity(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identityService.identityOf(UUID.fromString(jwt.getSubject())));
    }
}
