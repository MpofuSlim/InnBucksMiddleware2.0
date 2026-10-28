package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What was kept: the RE-ENCODED image's size, not the upload's. */
@Schema(name = "KycFileStored")
public record KycFileStoredView(

        @Schema(example = "id_front")
        String kind,

        @Schema(example = "1")
        int round,

        @Schema(description = "Width of the stored image (scaled down to 1600 px for documents, 1080 for the "
                + "selfie).", example = "1600")
        int width,

        @Schema(example = "1010")
        int height,

        @Schema(description = "Size of the stored JPEG in bytes.", example = "287412")
        int byteSize,

        @Schema(description = "True if this replaced a file of the same kind in this round.", example = "false")
        boolean replaced,

        @Schema(description = "Files still to upload in this round; empty = ready to submit.",
                example = "[\"id_back\",\"selfie\"]")
        List<String> missing
) {
}
