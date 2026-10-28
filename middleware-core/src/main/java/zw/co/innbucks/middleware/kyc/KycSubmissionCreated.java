package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Also what a replay with the same Idempotency-Key returns: it holds nothing sensitive. */
@Schema(name = "KycSubmissionCreated")
public record KycSubmissionCreated(

        @Schema(example = "b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d")
        String id,

        @Schema(example = "1")
        int round,

        @Schema(description = "The files to upload before submitting.",
                example = "[\"id_front\",\"id_back\",\"selfie\"]")
        List<String> needs
) {
}
