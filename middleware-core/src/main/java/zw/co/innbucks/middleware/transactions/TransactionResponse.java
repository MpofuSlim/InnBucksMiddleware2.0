package zw.co.innbucks.middleware.transactions;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Outcome of a money movement. PROCESSING means the outcome is not yet "
        + "proven — the middleware reconciles it in the background; the movement must NOT be retried "
        + "with a new key until it resolves.")
public record TransactionResponse(

        @Schema(description = "The middleware's ledger id for this movement.",
                example = "7b1e2d3c-4f5a-4b6c-8d9e-0a1b2c3d4e5f")
        UUID transactionId,

        @Schema(description = "SUCCESS | PROCESSING | FAILED", example = "SUCCESS")
        String status,

        @Schema(description = "The core banking system's reference, when known.",
                example = "501", nullable = true)
        String coreTxRef,

        @Schema(description = "Machine-readable failure code when status is FAILED.",
                example = "core_rejected", nullable = true)
        String failureCode,

        @Schema(description = "Human-readable failure detail when status is FAILED.",
                example = "Insufficient account balance.", nullable = true)
        String failureMessage
) {

    public static TransactionResponse success(UUID id, String coreTxRef) {
        return new TransactionResponse(id, "SUCCESS", coreTxRef, null, null);
    }

    public static TransactionResponse processing(UUID id, String coreTxRef) {
        return new TransactionResponse(id, "PROCESSING", coreTxRef, null, null);
    }

    public static TransactionResponse failed(UUID id, String code, String message) {
        return new TransactionResponse(id, "FAILED", null, code, message);
    }
}
