package zw.co.innbucks.middleware.auth.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import zw.co.innbucks.middleware.auth.exception.MalformedRequestException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.auth.login.LoginResult;
import zw.co.innbucks.middleware.auth.login.LoginService;
import zw.co.innbucks.middleware.auth.refresh.RefreshToken;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "auth", description = "Customer login, refresh, and logout. All three endpoints are unauthenticated; the access token is the proof of identity for everything else.")
@SecurityRequirements({})
public class LoginController {

    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final CustomerRepository customerRepository;
    private final JwtIssuer jwtIssuer;
    private final AuthProperties authProperties;
    private final AuditService auditService;

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate with MSISDN and PIN",
            description = "Issues a short-lived access token + a rotating refresh token. Failed attempts trigger exponential backoff; persistent failure locks the account."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or MSISDN format unrecognised",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "423", description = "Account is locked after too many failed attempts",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Backoff active — retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request.msisdn(), request.pin(), request.deviceHash());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate the refresh token, issuing a fresh access + refresh pair",
            description = "Each refresh token is single-use. Re-presenting an already-rotated refresh token is treated as theft and revokes the whole token family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens rotated"),
            @ApiResponse(responseCode = "401", description = "Invalid, expired, or replay-detected refresh token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        String secret = parseRefreshTokenUuid(request.refreshToken()).toString();

        RefreshToken rotated = refreshTokenService.rotate(secret, request.deviceHash());

        Customer customer = customerRepository.findById(rotated.getCustomerId())
                .orElseThrow(IllegalStateException::new);

        String accessToken = jwtIssuer.issue(new JwtIssuer.IssueRequest(
                customer.getId().toString(),
                customer.countryEnum(),
                customer.getKycTier(),
                CustomerScopes.DEFAULT,
                request.deviceHash(),
                customer.getNationalIdHash()
        ));

        return ResponseEntity.ok(new TokenResponse(
                accessToken,
                rotated.getSecret(),
                "Bearer",
                authProperties.accessTokenTtl().toSeconds()
        ));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Revoke the refresh token family",
            description = "Idempotent — revoking an already-revoked token returns 204."
    )
    @ApiResponse(responseCode = "204", description = "Logout recorded; refresh token family revoked")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        String secret = parseRefreshTokenUuid(request.refreshToken()).toString();
        refreshTokenService.revokeFamily(secret, "logout").ifPresent(rt ->
                auditService.record(AuditAction.LOGOUT, AuditOutcome.SUCCESS,
                        rt.getCustomerId(), rt.getDeviceHash()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Validate a caller-supplied refresh token as a UUID and return its
     * canonical form. The token is an opaque random secret that happens to be
     * UUID-shaped on the wire. Surfaces a {@link MalformedRequestException}
     * (-> 400 via AuthExceptionHandler) on malformed input instead of letting
     * the raw IllegalArgumentException bubble up. The previous global IAE
     * handler turned every IAE into a 400, which silently hid genuine
     * programmer bugs (e.g. PinHasher.hash(null)) from error-rate dashboards
     * as client errors.
     */
    private static UUID parseRefreshTokenUuid(String supplied) {
        try {
            return UUID.fromString(supplied);
        } catch (IllegalArgumentException ex) {
            throw new MalformedRequestException("refreshToken is not a valid UUID");
        }
    }

    private TokenResponse toResponse(LoginResult result) {
        return new TokenResponse(
                result.accessToken(),
                result.refreshToken(),
                "Bearer",
                result.accessTokenTtl().toSeconds());
    }

    @Schema(description = "Login request.")
    public record LoginRequest(
            @NotBlank @Schema(description = "Customer mobile number. Accepts +254..., 254..., or 07... formats.", example = "0712345678")
            String msisdn,

            @NotBlank @Size(min = 4, max = 6) @Schema(description = "4-6 digit numeric PIN.", example = "1234")
            String pin,

            @NotBlank @Schema(description = "Stable device fingerprint hash. Bound into the issued JWT.", example = "sha256-hex-of-device-fingerprint")
            String deviceHash
    ) {
    }

    @Schema(description = "Refresh request.")
    public record RefreshRequest(
            @NotBlank @Schema(description = "The opaque refresh token (UUID format) issued by /auth/login; single-use, replay revokes the whole family.", example = "550e8400-e29b-41d4-a716-446655440000")
            String refreshToken,

            @NotBlank @Schema(description = "Must match the device the refresh token was issued to.", example = "sha256-hex-of-device-fingerprint")
            String deviceHash
    ) {
    }

    @Schema(description = "Logout request.")
    public record LogoutRequest(
            @NotBlank @Schema(description = "The current opaque refresh token (UUID format); single-use, replay revokes the whole family. All tokens in its family will be revoked.", example = "550e8400-e29b-41d4-a716-446655440000")
            String refreshToken
    ) {
    }

    @Schema(description = "Token pair issued on login or refresh.")
    public record TokenResponse(
            @Schema(description = "HS256 JWT. Use as Bearer for every authenticated endpoint.")
            String accessToken,

            @Schema(description = "Opaque refresh token (UUID format); single-use, replay revokes the whole family.", example = "550e8400-e29b-41d4-a716-446655440000")
            String refreshToken,

            @Schema(description = "Always 'Bearer'.", example = "Bearer")
            String tokenType,

            @Schema(description = "Access-token lifetime in seconds.", example = "600")
            long expiresInSeconds
    ) {
    }
}
