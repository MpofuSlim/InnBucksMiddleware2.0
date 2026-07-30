package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "The authenticated customer's profile. Identity fields come from the "
        + "middleware; names come from the core banking system.")
public record ProfileResponse(

        @Schema(example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f")
        UUID customerId,

        @Schema(description = "Normalized MSISDN.", example = "+254712345678")
        String msisdn,

        @Schema(example = "Tariro")
        String firstName,

        @Schema(example = "Moyo")
        String lastName,

        @Schema(description = "Local customer status.", example = "active")
        String status,

        @Schema(description = "KYC tier driving limits.", example = "basic")
        String kycTier
) {
}
