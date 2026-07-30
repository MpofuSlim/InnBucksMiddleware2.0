package zw.co.innbucks.middleware.stepup;

import lombok.Getter;

/**
 * A movement at or above the caller's tier threshold arrived without a valid
 * step-up approval. Carries the server-computed transaction fingerprint so
 * the 403 response can hand the app exactly the value to approve:
 * {@code POST /auth/step-up/request} → SMS code →
 * {@code POST /auth/step-up/verify {code, txnFp}} → retry the movement with
 * the returned token in {@code X-Step-Up-Token}.
 */
@Getter
public class StepUpRequiredException extends RuntimeException {

    private final String txnFp;

    public StepUpRequiredException(String txnFp) {
        super("Step-up approval required for this movement");
        this.txnFp = txnFp;
    }
}
