package zw.co.innbucks.middleware.otp.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.auth.exception.MalformedRequestException;
import zw.co.innbucks.middleware.common.msisdn.InvalidMsisdnException;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.OtpService;
import zw.co.innbucks.middleware.otp.OtpVerificationException;
import zw.co.innbucks.middleware.otp.VerificationToken;

import java.net.URI;

@Slf4j
@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
@Tag(name = "auth-otp",
        description = "Phone-ownership verification via SMS one-time-passcode. Gates PIN setup and PIN reset.")
@SecurityRequirements({})
public class OtpController {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    private final OtpService otpService;

    @PostMapping("/request")
    @Operation(summary = "Request a one-time code by SMS",
            description = "Generates a fresh OTP for (msisdn, purpose) and sends it via the configured SMS provider. "
                    + "Any prior outstanding code for the same (msisdn, purpose) is invalidated. Always returns 204 — "
                    + "never reveals whether the MSISDN is recognised, to avoid account enumeration.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "OTP request accepted (SMS dispatched). No body."),
            @ApiResponse(responseCode = "429",
                    description = "Too many OTP requests for this number or source IP; retry after Retry-After seconds.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> request(@Valid @RequestBody OtpRequestPayload payload) {
        rejectStepUpPurpose(payload.purpose());
        try {
            otpService.request(payload.msisdn(), payload.purpose());
        } catch (InvalidMsisdnException ex) {
            // Anti-enumeration: a malformed/non-Kenyan MSISDN would otherwise
            // bubble to AuthExceptionHandler and surface as 401 invalid_credentials,
            // which distinguishes "this MSISDN shape exists" from "this MSISDN
            // shape is invalid" — defeating the always-204 contract above.
            // Swallow locally and return 204 so every caller sees the same
            // response regardless of input shape or account state.
            log.debug("OTP request rejected for malformed MSISDN; returning 204 anyway");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify a one-time code",
            description = "On success, returns a short-lived verification token (JWT) the caller passes to "
                    + "/auth/pin/set or /auth/pin/reset (next slice). The OTP is consumed on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP accepted; verification token issued."),
            @ApiResponse(responseCode = "401", description = "OTP missing / expired / wrong / attempts exhausted.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public OtpVerifyResponse verify(@Valid @RequestBody OtpVerifyPayload payload) {
        rejectStepUpPurpose(payload.purpose());
        VerificationToken token = otpService.verify(payload.msisdn(), payload.purpose(), payload.code());
        return new OtpVerifyResponse(token.token(), "Bearer", token.ttl().toSeconds(), payload.purpose());
    }

    /**
     * STEP_UP is served ONLY by the authenticated /auth/step-up endpoints:
     * there the OTP goes to the LOGGED-IN customer's registered MSISDN and the
     * token gets its txn_fp binding. Accepting it here would let an
     * unauthenticated caller SMS approval codes to arbitrary numbers and mint
     * unbound STEP_UP tokens.
     */
    private static void rejectStepUpPurpose(OtpPurpose purpose) {
        if (purpose == OtpPurpose.STEP_UP) {
            throw new MalformedRequestException("purpose STEP_UP is not available on this endpoint");
        }
    }

    /**
     * Covers {@code /verify} only — {@code /request} swallows this inline to
     * keep its always-204 contract.
     *
     * <p>{@code OtpService.verify} normalises the MSISDN before it looks at
     * any challenge, so a badly-shaped number threw
     * {@code InvalidMsisdnException} out of this controller and landed in
     * {@code AuthExceptionHandler}: a 401 reading "The phone number or PIN you
     * entered is incorrect" on an OTP screen that has no PIN field. Nothing
     * about the number's SHAPE is a secret — the app applies the same rules
     * client-side — so answering with a plain 400 leaks nothing and tells the
     * caller what is actually wrong. Whether a challenge EXISTS stays
     * concealed, which is the part that matters, and is handled below.
     */
    @ExceptionHandler(InvalidMsisdnException.class)
    public ResponseEntity<ProblemDetail> invalidMsisdn(InvalidMsisdnException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Check the phone number");
        body.setDetail("That phone number doesn't look right. "
                + "Please enter it in the format used in your country and try again.");
        body.setProperty("errorCode", "invalid_msisdn");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(OtpVerificationException.class)
    public ResponseEntity<ProblemDetail> verificationFailure(OtpVerificationException ex) {
        log.warn("OTP verification failed: {}", ex.getReason());
        String errorCode = switch (ex.getReason()) {
            case NO_ACTIVE_CHALLENGE -> "otp_not_found";
            case EXPIRED -> "otp_expired";
            case ATTEMPTS_EXHAUSTED -> "otp_attempts_exhausted";
            case CODE_MISMATCH -> "otp_invalid";
        };
        String detail = switch (ex.getReason()) {
            case NO_ACTIVE_CHALLENGE ->
                    "We couldn't find an active verification for this number. Please request a new code.";
            case EXPIRED -> "This verification code has expired. Please request a new one.";
            case ATTEMPTS_EXHAUSTED ->
                    "Too many incorrect attempts. Please request a new verification code.";
            case CODE_MISMATCH -> "That code doesn't match. Please check the code and try again.";
        };
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Verification code didn't work");
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @Schema(name = "OtpRequestPayload")
    public record OtpRequestPayload(
            @NotBlank @Schema(description = "Mobile number. Accepts +254..., 254..., or 07... formats.",
                    example = "0712345678")
            String msisdn,

            @NotNull @Schema(description = "What the OTP is for.", example = "PIN_SETUP")
            OtpPurpose purpose
    ) {
    }

    @Schema(name = "OtpVerifyPayload")
    public record OtpVerifyPayload(
            @NotBlank @Schema(example = "0712345678") String msisdn,

            @NotNull OtpPurpose purpose,

            @NotBlank @Pattern(regexp = "^[0-9]{4,8}$", message = "OTP code must be 4-8 digits")
            @Schema(description = "The one-time code from SMS.", example = "000000")
            String code
    ) {
    }

    @Schema(name = "OtpVerifyResponse",
            description = "Short-lived verification token. Pass it to /auth/pin/set or /auth/pin/reset.")
    public record OtpVerifyResponse(
            @Schema(description = "Verification JWT. Opaque to the caller; only /auth/pin/{set,reset} accepts it.")
            String verificationToken,

            @Schema(example = "Bearer") String tokenType,

            @Schema(example = "300") long expiresInSeconds,

            @Schema(description = "The purpose this token is bound to.") OtpPurpose purpose
    ) {
    }
}
