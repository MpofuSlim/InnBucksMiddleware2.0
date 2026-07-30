package zw.co.innbucks.middleware.register;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Registration outcome. The customer is created in the core banking system "
        + "with an active wallet account, and locally awaits PIN setup.")
public record RegisterResponse(

        @Schema(description = "The customer's stable middleware id — also the customer's externalId in the core.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f")
        UUID customerId,

        @Schema(description = "Local customer status. Always pending_verification after registration — "
                + "the mobile app routes to OTP + PIN setup next.", example = "pending_verification")
        String status,

        @Schema(description = "The wallet account's stable reference in the core.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
        String walletAccountId
) {
}
