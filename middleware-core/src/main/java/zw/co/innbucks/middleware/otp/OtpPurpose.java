package zw.co.innbucks.middleware.otp;

/**
 * Why an OTP was issued. A verification token is scoped to one purpose; using
 * it on the wrong endpoint must fail. Future purposes for step-up auth and
 * device registration are added here as the slices ship.
 */
public enum OtpPurpose {

    /** Setting a PIN for the first time after registration. */
    PIN_SETUP,

    /** Resetting a forgotten PIN on an ACTIVE customer. */
    PIN_RESET
}
