package zw.co.innbucks.middleware.me;

import java.util.UUID;

/** No usable customer behind the token's subject (row missing or core mapping absent). Maps to 404. */
public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(UUID customerId) {
        super("No registered customer for id " + customerId);
    }
}
