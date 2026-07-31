package zw.co.innbucks.middleware.register.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.common.msisdn.InvalidMsisdnException;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.register.CustomerAlreadyExistsException;
import zw.co.innbucks.middleware.register.RegisterRequest;
import zw.co.innbucks.middleware.register.RegisterResponse;
import zw.co.innbucks.middleware.register.RegisterService;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@Tag(name = "register", description = "Public customer onboarding. Creates the customer + an active "
        + "wallet in the core banking system; the customer then completes OTP + PIN setup before login.")
public class RegisterController {

    private final RegisterService registerService;

    @PostMapping("/register")
    @SecurityRequirements({})
    @Operation(
            summary = "Register a new customer",
            description = "Requires a caller-supplied Idempotency-Key (UUID). The key is namespaced "
                    + "per MSISDN server-side: retries with the same key replay the original response; "
                    + "the same key with a different body is rejected. On success the customer exists "
                    + "in the core with an ACTIVE wallet and locally awaits PIN setup "
                    + "(/auth/otp/request → /auth/otp/verify → /auth/pin/set).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer + wallet created (or replayed)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RegisterResponse.class),
                            examples = @ExampleObject(value = """
                                    {"customerId":"3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f",
                                     "status":"pending_verification",
                                     "walletAccountId":"3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Invalid MSISDN or missing fields/header",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "MSISDN already registered, or an identical "
                    + "request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body, "
                    + "or the core rejected the registration",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<RegisterResponse> register(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RegisterRequest request) {
        IdempotencyService.Result<RegisterResponse> result =
                registerService.register(idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * A malformed phone number on SIGN-UP is a validation failure, not an
     * authentication one.
     *
     * <p>Without this, {@code InvalidMsisdnException} falls through to
     * {@code AuthExceptionHandler}, which answers every one of them with
     * login's deliberately-vague 401 — "The phone number or PIN you entered is
     * incorrect" — on a screen that has no PIN field and no sign-in to fail.
     * That anti-enumeration wording earns its keep on {@code /auth/login};
     * here it earns nothing. Registration already distinguishes a known number
     * (409 above) by design, so a distinct 400 for a badly-shaped one leaks
     * nothing that endpoint doesn't already tell you.
     */
    @ExceptionHandler(InvalidMsisdnException.class)
    public ResponseEntity<ProblemDetail> invalidMsisdn(InvalidMsisdnException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        body.setType(URI.create("about:blank"));
        body.setTitle("Check the phone number");
        body.setDetail("That phone number doesn't look right. "
                + "Please enter it in the format used in your country and try again.");
        body.setProperty("errorCode", "invalid_msisdn");
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(CustomerAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> alreadyRegistered(CustomerAlreadyExistsException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        body.setType(URI.create("about:blank"));
        body.setTitle("Already registered");
        body.setDetail("This phone number is already registered. Sign in instead, or reset the PIN.");
        body.setProperty("errorCode", "customer_already_registered");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
