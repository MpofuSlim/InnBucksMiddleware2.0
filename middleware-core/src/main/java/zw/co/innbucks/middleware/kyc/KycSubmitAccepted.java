package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "KycSubmitAccepted")
public record KycSubmitAccepted(

        @Schema(example = "pending_review", allowableValues = {"pending_review"})
        String status,

        @Schema(description = "The round now with staff; its files and ID can no longer change.", example = "1")
        int round
) {
}
