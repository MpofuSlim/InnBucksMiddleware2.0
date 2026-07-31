package zw.co.innbucks.middleware.transactions;

/**
 * The looked-up number cannot receive money right now. Deliberately carries NO
 * reason — unknown number, unregistered, no core mapping and no wallet must be
 * indistinguishable to the caller (the reasons live in logs + metrics).
 */
public class RecipientNotFoundException extends RuntimeException {

    public RecipientNotFoundException() {
        super("Recipient not found");
    }
}
