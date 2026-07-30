package zw.co.innbucks.middleware.transactions;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Money-movement request shapes. Amounts are MINOR units (cents) — the same
 * unit /me/accounts reports balances in; the middleware never parses decimal
 * money from the app. Currency is explicit and must match the cell's.
 */
public final class MovementRequests {

    private MovementRequests() {
    }

    @Schema(description = "Deposit into one of the caller's own accounts.")
    public record DepositRequest(
            @NotBlank
            @Schema(description = "Target account (must belong to the caller).",
                    example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
            String accountId,

            @Min(1)
            @Schema(description = "Amount in MINOR units.", example = "250000")
            long amountMinor,

            @NotBlank
            @Schema(example = "KES")
            String currency,

            @Size(max = 200)
            @Schema(example = "Cash in", nullable = true)
            String narrative
    ) {
    }

    @Schema(description = "Withdrawal from one of the caller's own accounts.")
    public record WithdrawRequest(
            @NotBlank
            @Schema(description = "Source account (must belong to the caller).",
                    example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
            String accountId,

            @Min(1)
            @Schema(description = "Amount in MINOR units.", example = "100000")
            long amountMinor,

            @NotBlank
            @Schema(example = "KES")
            String currency,

            @Size(max = 200)
            @Schema(example = "Cash out", nullable = true)
            String narrative
    ) {
    }

    @Schema(description = "Transfer from one of the caller's own accounts to any account in the cell.")
    public record TransferRequest(
            @NotBlank
            @Schema(description = "Source account (must belong to the caller).",
                    example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
            String fromAccountId,

            @NotBlank
            @Schema(description = "Destination account reference.",
                    example = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet")
            String toAccountId,

            @Min(1)
            @Schema(description = "Amount in MINOR units.", example = "50000")
            long amountMinor,

            @NotBlank
            @Schema(example = "KES")
            String currency,

            @Size(max = 200)
            @Schema(example = "Rent", nullable = true)
            String narrative
    ) {
    }
}
