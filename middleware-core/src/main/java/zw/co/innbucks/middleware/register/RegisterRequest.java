package zw.co.innbucks.middleware.register;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "New-customer registration. No PIN here — the customer lands in "
        + "PENDING_VERIFICATION and sets a PIN via the OTP-gated /auth/otp + /auth/pin/set flow.")
public record RegisterRequest(

        @NotBlank
        @Schema(description = "Customer MSISDN in any accepted national/international format; normalized per the cell's country.",
                example = "+254712345678")
        String msisdn,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "Legal first name.", example = "Tariro")
        String firstName,

        @NotBlank
        @Size(max = 100)
        @Schema(description = "Legal last name.", example = "Moyo")
        String lastName,

        @Size(max = 50)
        @Schema(description = "National ID number (optional). Stored keyed-hashed (HMAC-SHA256), never plaintext.",
                example = "12345678", nullable = true)
        String nationalId
) {
}
