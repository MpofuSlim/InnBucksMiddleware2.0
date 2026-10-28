package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Opens a verification request. Validated in the service (not by bean
 * validation) so each field answers its own {@code errorCode}.
 *
 * <p>{@link #toString()} is overridden: a record's generated one would print
 * the ID number and the address into any log line that touches the request.
 */
@Schema(name = "CreateKycSubmission")
public record CreateKycSubmissionRequest(

        @Schema(description = "A tier above the caller's current one.", example = "standard",
                allowableValues = {"standard", "enhanced"})
        String targetTier,

        @Schema(description = "The national ID number as on the card. Spaces, dashes and letter case are "
                + "ignored. Stored encrypted; only a masked form ever comes back.",
                example = "63-1234567 X 18")
        String nationalId,

        @Schema(description = "Residential address — required for enhanced, refused otherwise. Up to 500 "
                + "characters. Stored encrypted.", example = "12 Example Road, Harare",
                nullable = true)
        String address
) {

    @Override
    public String toString() {
        return "CreateKycSubmissionRequest[targetTier=" + targetTier + ", nationalId=<redacted>, address="
                + (address == null ? "null" : "<redacted>") + "]";
    }
}
