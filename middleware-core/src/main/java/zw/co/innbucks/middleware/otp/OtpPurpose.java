package zw.co.innbucks.middleware.otp;

/**
 * Why an OTP was issued. A verification token is scoped to one purpose; using
 * it on the wrong endpoint must fail. Future purposes (device registration,
 * ...) are added here as the slices ship.
 */
public enum OtpPurpose {

    /** Setting a PIN for the first time after registration. */
    PIN_SETUP,

    /** Resetting a forgotten PIN on an ACTIVE customer. */
    PIN_RESET,

    /**
     * Step-up approval of a single high-value money movement. AUTHENTICATED
     * flow only — served by {@code /auth/step-up/*}, never the public
     * {@code /auth/otp/*} endpoints (which reject this purpose): the OTP goes
     * to the LOGGED-IN customer's registered MSISDN, and the minted token is
     * bound to one transaction fingerprint ({@code txn_fp} claim).
     */
    STEP_UP
}
