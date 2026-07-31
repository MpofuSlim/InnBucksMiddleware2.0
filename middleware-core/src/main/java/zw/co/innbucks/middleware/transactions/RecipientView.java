package zw.co.innbucks.middleware.transactions;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A transfer recipient resolved from a phone number. Deliberately minimal: "
        + "masked display name for the sender's confirmation screen, and the accountId to pass "
        + "as toAccountId on POST /transactions/transfer. Never balance, status or KYC data.")
public record RecipientView(

        @Schema(description = "The recipient's wallet — use as toAccountId on the transfer.",
                example = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet")
        String accountId,

        @Schema(description = "Masked display name for the confirm screen — first name + surname initial.",
                example = "Tariro M.")
        String displayName,

        @Schema(description = "The number as normalised (E.164) — render this on the confirm screen "
                + "so the sender sees exactly which number will be paid.",
                example = "+263771234567")
        String msisdn
) {
}
