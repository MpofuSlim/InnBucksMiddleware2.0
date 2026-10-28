package zw.co.innbucks.middleware.kyc;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "KycStatus",
        description = "The caller's KYC tier, what the next tier needs, what is on file and the current "
                + "verification request. Never carries the full ID number, the address or any image.")
public record KycStatusView(

        @Schema(description = "The tier payments are judged by now.", example = "basic",
                allowableValues = {"basic", "standard", "enhanced"})
        String kycTier,

        @Schema(description = "The tier above, and everything a request for it collects. Null at enhanced.",
                nullable = true)
        NextTier nextTier,

        @Schema(description = "The evidence of record: what the latest APPROVED request holds. All false and "
                + "nationalId null until a request is approved. A request that is still a draft, pending or "
                + "rejected is described by `submission`, never here — so this does not change when a later "
                + "request is rejected or a rejected one's photos are deleted.")
        OnFile onFile,

        @Schema(description = "The open request if there is one, else the most recent; null if none was ever "
                + "made.", nullable = true)
        SubmissionView submission
) {

    @Schema(name = "KycNextTier")
    public record NextTier(
            @Schema(example = "standard", allowableValues = {"standard", "enhanced"})
            String tier,
            @Schema(description = "national_id, id_front, id_back, selfie; enhanced adds address and "
                    + "proof_of_address.", example = "[\"national_id\",\"id_front\",\"id_back\",\"selfie\"]")
            List<String> needs) {
    }

    @Schema(name = "KycOnFile")
    public record OnFile(
            @Schema(description = "Masked: the first 2 and last 3 characters (only the last 3 for a short "
                    + "number). The full number never comes back.", example = "63•••••••X18", nullable = true)
            String nationalId,
            boolean idFront,
            boolean idBack,
            boolean selfie,
            boolean proofOfAddress,
            boolean address) {

        static final OnFile NOTHING = new OnFile(null, false, false, false, false, false);
    }

    @Schema(name = "KycSubmission")
    public record SubmissionView(
            @Schema(example = "b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d")
            String id,
            @Schema(example = "standard", allowableValues = {"standard", "enhanced"})
            String targetTier,
            @Schema(description = "The ID number this request was made with, masked: the first 2 and last 3 "
                    + "characters (only the last 3 for a short number). The full number never comes back.",
                    example = "63•••••••X18")
            String nationalId,
            @Schema(description = "draft: collecting files. pending_review: with staff. rejected: see "
                    + "`rejection`; when `missing` is not null the request was reopened — upload only those "
                    + "files and submit again. approved. expired: not finished in time; start a new one.",
                    example = "rejected",
                    allowableValues = {"draft", "pending_review", "approved", "rejected", "expired"})
            String status,
            @Schema(description = "Which attempt this is: 1, then +1 each time a rejection reopens it.",
                    example = "2")
            int round,
            @Schema(description = "When it was last submitted, at the market offset.",
                    example = "2026-10-28T09:14:00+02:00", nullable = true)
            String submittedAt,
            @Schema(description = "When it was last decided, at the market offset; null while pending.",
                    example = "2026-10-28T15:02:00+02:00", nullable = true)
            String decidedAt,
            @Schema(description = "Why it was rejected and which files to retake; null unless rejected.",
                    nullable = true)
            Rejection rejection,
            @Schema(description = "Files still to upload before it can be submitted; null when the customer "
                    + "cannot change it (pending, decided, expired).",
                    example = "[\"id_front\"]", nullable = true)
            List<String> missing,
            @Schema(description = "Finish (upload + submit) before this or it expires; null when there is "
                    + "nothing to finish.", example = "2026-11-11T15:02:00+02:00", nullable = true)
            String expiresAt) {
    }

    @Schema(name = "KycRejection")
    public record Rejection(
            @Schema(example = "[\"id_photo_unreadable\"]", description = "id_photo_unreadable, id_expired, "
                    + "name_mismatch, face_mismatch, selfie_not_live, address_unreadable, other")
            List<String> reasons,
            @Schema(example = "[\"id_front\"]", description = "The files to retake. Empty: this request is "
                    + "closed; start a new one.")
            List<String> redo) {
    }
}
