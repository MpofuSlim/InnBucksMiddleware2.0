package zw.co.innbucks.middleware.audit;

public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGIN_BLOCKED_LOCKED,
    LOGIN_BLOCKED_BACKOFF,
    ACCOUNT_LOCKED,
    REFRESH_SUCCESS,
    REFRESH_REPLAY_DETECTED,
    REFRESH_DEVICE_MISMATCH,
    REFRESH_INVALID,
    LOGOUT,
    REGISTER_SUCCESS,
    REGISTER_FAILURE,
    PIN_NOT_SET,
    OTP_REQUESTED,
    OTP_VERIFIED,
    OTP_VERIFY_FAILED,
    PIN_SET,
    PIN_RESET,
    PIN_SET_REJECTED,
    STEP_UP_APPROVED,
    STEP_UP_REJECTED,
    /**
     * One source failed authentication against enough DIFFERENT accounts inside
     * the detection window to be a credential spray. Recorded once per source
     * per window (never per attempt — that would serialise an attack on the
     * audit chain's row lock), with the source and distinct-account count in
     * the metadata.
     */
    CREDENTIAL_SPRAY_DETECTED,
    TRANSFER_DEPOSIT_SUCCESS,
    TRANSFER_DEPOSIT_FAILURE,
    WITHDRAWAL_SUCCESS,
    WITHDRAWAL_FAILURE,
    // Ledger lifecycle (money-significant transitions sealed by LedgerService).
    TXN_COMPLETED,
    TXN_FAILED,
    TXN_UNKNOWN,
    /**
     * The per-tier payment-limits policy this process enforces, written once
     * per boot: source file, its SHA-256, country, currency, dormant flag, the
     * number of active overrides and any tier inversions they left
     * ({@code warnings}). With the deployed image sha this answers "which
     * limits were in force when".
     */
    LIMITS_POLICY_LOADED,
    /**
     * One effective limit value an emergency (tighten-only) override changed,
     * in force at boot: tier, kind, the file's value and the enforced one, plus
     * {@code derivedFrom} when it was lowered to keep {@code per_payment <=
     * daily <= monthly} after another override. Written per value, every boot,
     * so an override left set for months stays visible in the trail.
     */
    LIMITS_OVERRIDE_ACTIVE;

    public String dbValue() {
        return name().toLowerCase();
    }
}
