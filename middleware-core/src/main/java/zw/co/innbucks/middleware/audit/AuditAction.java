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
    TXN_UNKNOWN;

    public String dbValue() {
        return name().toLowerCase();
    }
}
