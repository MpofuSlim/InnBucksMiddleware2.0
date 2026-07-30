package zw.co.innbucks.middleware.stepup.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.OtpService;
import zw.co.innbucks.middleware.otp.OtpVerificationException;
import zw.co.innbucks.middleware.otp.VerificationToken;
import zw.co.innbucks.middleware.otp.VerificationTokenIssuer;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * AUTHENTICATED step-up approval for high-value movements. Deliberately
 * separate from the public {@code /auth/otp/*} endpoints: the OTP goes to the
 * LOGGED-IN customer's registered MSISDN (never a caller-supplied number),
 * and the minted token is bound to one transaction fingerprint. Flow: a
 * movement 403s with {@code step_up_required} + {@code txnFp} → the app calls
 * {@code /request} (SMS) → {@code /verify} with the code + that fingerprint →
 * retries the movement with the returned token in {@code X-Step-Up-Token}.
 */
@Slf4j
@RestController
@RequestMapping("/auth/step-up")
@RequiredArgsConstructor
@Tag(name = "auth-step-up",
        description = "SMS approval of a single high-value movement. Authenticated; the code goes to "
                + "the logged-in customer's registered number, and the approval token is valid for "
                + "exactly one transaction (the fingerprint from the 403 step_up_required response).")
public class StepUpController {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    private final OtpService otpService;
    private final CustomerRepository customerRepository;

    @PostMapping("/request")
    @Operation(summary = "Send a step-up approval code by SMS",
            description = "Sends a one-time code to the AUTHENTICATED customer's registered mobile number. "
                    + "Any prior outstanding step-up code is invalidated. Rate-limited per IP and per MSISDN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Code dispatched to the registered number. No body."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer for this login",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests; retry after Retry-After seconds.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "SMS could not be dispatched; safe to retry.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<Void> request(@AuthenticationPrincipal Jwt jwt) {
        Customer customer = requireCustomer(jwt);
        otpService.request(customer.getMsisdn(), OtpPurpose.STEP_UP);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify the approval code and mint a transaction-bound token",
            description = "On success returns a short-lived, SINGLE-USE approval token bound to the supplied "
                    + "transaction fingerprint (the txnFp from the movement's 403 step_up_required response). "
                    + "Pass it in the X-Step-Up-Token header when retrying that exact movement — any other "
                    + "movement rejects it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code accepted; approval token issued.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = StepUpVerifyResponse.class))),
            @ApiResponse(responseCode = "401", description = "Code missing / expired / wrong / attempts exhausted.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer for this login",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public StepUpVerifyResponse verify(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody StepUpVerifyPayload payload) {
        Customer customer = requireCustomer(jwt);
        VerificationToken token = otpService.verify(
                customer.getMsisdn(), OtpPurpose.STEP_UP, payload.code(),
                Map.of(VerificationTokenIssuer.CLAIM_TXN_FP, payload.txnFp()));
        return new StepUpVerifyResponse(token.token(), "Bearer", token.ttl().toSeconds());
    }

    private Customer requireCustomer(Jwt jwt) {
        UUID customerId = UUID.fromString(jwt.getSubject());
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
    }

    @ExceptionHandler(OtpVerificationException.class)
    public ResponseEntity<ProblemDetail> verificationFailure(OtpVerificationException ex) {
        // Same surface as OtpController's handler — the app treats both flows alike.
        log.warn("Step-up OTP verification failed: {}", ex.getReason());
        String errorCode = switch (ex.getReason()) {
            case NO_ACTIVE_CHALLENGE -> "otp_not_found";
            case EXPIRED -> "otp_expired";
            case ATTEMPTS_EXHAUSTED -> "otp_attempts_exhausted";
            case CODE_MISMATCH -> "otp_invalid";
        };
        String detail = switch (ex.getReason()) {
            case NO_ACTIVE_CHALLENGE ->
                    "We couldn't find an active approval request. Please request a new code.";
            case EXPIRED -> "This approval code has expired. Please request a new one.";
            case ATTEMPTS_EXHAUSTED -> "Too many incorrect attempts. Please request a new approval code.";
            case CODE_MISMATCH -> "That code doesn't match. Please check the code and try again.";
        };
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Approval code didn't work");
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(ProfileNotFoundException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Not registered");
        body.setDetail("We couldn't find a registered account for this login. Please register first.");
        body.setProperty("errorCode", "customer_not_registered");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @Schema(name = "StepUpVerifyPayload")
    public record StepUpVerifyPayload(

            @NotBlank @Pattern(regexp = "^[0-9]{4,8}$", message = "OTP code must be 4-8 digits")
            @Schema(description = "The one-time approval code from SMS.", example = "000000")
            String code,

            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "txnFp must be the 64-hex fingerprint from the 403 response")
            @Schema(description = "The transaction fingerprint echoed by the movement's 403 step_up_required response.",
                    example = "9f2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e")
            String txnFp
    ) {
    }

    @Schema(name = "StepUpVerifyResponse",
            description = "Single-use approval token bound to one transaction. Pass as X-Step-Up-Token when retrying that movement.")
    public record StepUpVerifyResponse(

            @Schema(description = "The approval token (JWT). Opaque to the app.")
            String verificationToken,

            @Schema(description = "Always 'Bearer'.", example = "Bearer")
            String tokenType,

            @Schema(description = "Token lifetime in seconds.", example = "300")
            long expiresInSeconds
    ) {
    }
}
