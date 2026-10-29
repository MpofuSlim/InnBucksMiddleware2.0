package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Who the customer behind an identity token is.")
public record IdentityResponse(

        @Schema(description = "The customer's stable id. Prefer it as a storage key: a phone number "
                + "can be recycled to someone else by the carrier.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f")
        UUID customerId,

        @Schema(description = "Normalized MSISDN.", example = "+263771234567")
        String msisdn
) {
}
