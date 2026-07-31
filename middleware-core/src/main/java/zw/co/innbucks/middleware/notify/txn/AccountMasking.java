package zw.co.innbucks.middleware.notify.txn;

/**
 * Renders an account reference for customer-facing copy: the last four
 * characters, never the whole thing. An SMS is delivered to a device that may
 * be shared, forwarded or read off a lock screen, so it carries only enough
 * to identify WHICH account moved — never enough to address one.
 *
 * <p>What gets masked is the account's core {@code externalId}, e.g.
 * {@code 3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet} → {@code 6e7f}. The
 * suffix after {@code :} is a slot name shared by every customer, so it is
 * dropped first — masking the tail of "wallet" would print the same four
 * characters for everyone. The result matches the tail of the {@code
 * accountId} the app already shows on {@code /me/accounts}, so a customer
 * comparing the two sees the same digits.
 */
public final class AccountMasking {

    private static final int KEEP_LAST = 4;
    private static final String UNKNOWN = "----";

    private AccountMasking() {
    }

    public static String maskAccount(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return UNKNOWN;
        }
        String local = externalId.trim();
        int slot = local.indexOf(':');
        if (slot >= 0) {
            // >= 0, not > 0: a malformed ":wallet" must fall through to the
            // placeholder below, NOT mask the slot name and print the same
            // four characters for every customer in the cell.
            local = local.substring(0, slot);
        }
        // Separators are not identifying; dropping them keeps the tail stable
        // whether the core's externalId is hyphenated or not.
        local = local.replaceAll("[^A-Za-z0-9]", "");
        if (local.isEmpty()) {
            return UNKNOWN;
        }
        return local.length() <= KEEP_LAST ? local : local.substring(local.length() - KEEP_LAST);
    }
}
