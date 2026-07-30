package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One of the customer's deposit/wallet accounts.")
public record AccountView(

        @Schema(description = "The account's stable reference — use it as accountId in /transactions/*.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
        String accountId,

        @Schema(description = "Product name from the core.", example = "InnBucks Wallet")
        String name,

        @Schema(example = "KES")
        String currency,

        @Schema(description = "Available balance in MINOR units (cents).", example = "125000")
        long balanceMinor
) {
}
