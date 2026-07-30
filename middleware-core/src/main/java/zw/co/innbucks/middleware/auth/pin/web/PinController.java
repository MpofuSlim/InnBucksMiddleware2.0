package zw.co.innbucks.middleware.auth.pin.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.auth.pin.PinService;

@RestController
@RequestMapping("/auth/pin")
@RequiredArgsConstructor
@Tag(name = "auth-pin",
        description = "Set or reset a customer PIN. Authentication is the verification token issued by /auth/otp/verify — not a Bearer access token.")
@SecurityRequirements({})
public class PinController {

    private final PinService pinService;

    @PostMapping("/set")
    @Operation(summary = "Set the customer's first PIN",
            description = "Consumes a verification token issued for purpose=PIN_SETUP. Flips the customer from "
                    + "PENDING_VERIFICATION to ACTIVE so they can /auth/login.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "PIN set; customer is now ACTIVE."),
            @ApiResponse(responseCode = "401", description = "Verification token missing / expired / wrong purpose / wrong audience.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Customer is not in a state that permits PIN_SETUP.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> setPin(@Valid @RequestBody PinPayload payload) {
        pinService.setInitialPin(payload.verificationToken(), payload.pin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset a customer's PIN (forgot-PIN flow)",
            description = "Consumes a verification token issued for purpose=PIN_RESET. Updates the PIN, clears any "
                    + "failed-attempt counters, and unlocks the customer if they were LOCKED.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "PIN reset; customer can /auth/login with the new PIN."),
            @ApiResponse(responseCode = "401", description = "Verification token missing / expired / wrong purpose.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Customer is not in a state that permits PIN_RESET.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> resetPin(@Valid @RequestBody PinPayload payload) {
        pinService.resetPin(payload.verificationToken(), payload.pin());
        return ResponseEntity.noContent().build();
    }

    @Schema(name = "PinPayload", description = "Body for /auth/pin/set and /auth/pin/reset.")
    public record PinPayload(
            @NotBlank
            @Schema(description = "Verification token from /auth/otp/verify (audience innbucks-otp-verify).")
            String verificationToken,

            @NotBlank
            @Pattern(regexp = "^[0-9]{4,6}$", message = "PIN must be 4-6 digits")
            @Size(min = 4, max = 6)
            @Schema(description = "4-6 digit numeric PIN; hashed with Argon2id server-side.", example = "1234")
            String pin
    ) {
    }
}
