package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "KycCancelled")
public record KycCancelled(

        @Schema(description = "Always expired: the request is closed and the files uploaded into its open round "
                + "are gone. Open a new one with POST /me/kyc/submissions.",
                example = "expired", allowableValues = {"expired"})
        String status,

        @Schema(description = "The round that was open when the request closed.", example = "1")
        int round
) {
}
