package zw.co.innbucks.middleware.kyc.nationalid;

/**
 * A typed national ID is not a well-formed number for the cell's country.
 * Maps to 400 {@code national_id_invalid}. The message says what is wrong
 * with the SHAPE only — it never repeats the number.
 */
public class InvalidNationalIdException extends RuntimeException {

    public InvalidNationalIdException(String message) {
        super(message);
    }
}
