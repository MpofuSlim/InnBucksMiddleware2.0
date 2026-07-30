package zw.co.innbucks.middleware.register;

/** The MSISDN already belongs to a fully registered customer (core mapping present). Maps to 409. */
public class CustomerAlreadyExistsException extends RuntimeException {

    public CustomerAlreadyExistsException(String maskedMsisdn) {
        super("A customer is already registered for " + maskedMsisdn);
    }
}
