package zw.co.innbucks.middleware.notify;

/**
 * A notification could not be handed to the InnBucks notification API —
 * blank input, missing configuration, an upstream rejection, or a
 * connectivity failure. Callers on the OTP path let it bubble: the OTP
 * transaction rolls back (no challenge row without a dispatched SMS) and the
 * customer sees a 503 they can retry, instead of waiting on a code that was
 * never sent.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
