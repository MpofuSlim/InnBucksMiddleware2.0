package zw.co.innbucks.middleware.register;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Registration outcome. The customer is created in the core banking system "
        + "with an active wallet account. A new customer then sets up a PIN; a resumed registration "
        + "may already have one (see status).")
public record RegisterResponse(

        @Schema(description = "The customer's stable middleware id — also the customer's externalId in the core.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f")
        UUID customerId,

        @Schema(description = "Local customer status. pending_verification: no PIN yet — the mobile app "
                + "routes to OTP + PIN setup next. active or locked: an earlier registration was resumed "
                + "and the PIN is already set, so skip PIN setup (it would answer 403 pin_set_not_allowed); "
                + "a signed-in customer carries on, anyone else signs in.",
                allowableValues = {"pending_verification", "active", "locked"},
                example = "pending_verification")
        String status,

        @Schema(description = "The wallet account's stable reference in the core.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
        String walletAccountId
) {
}
