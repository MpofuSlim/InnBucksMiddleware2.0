package zw.co.innbucks.middleware.notify.txn;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Customer-facing transaction alerts — the SMS a customer gets after money
 * actually moves.
 *
 * <p><b>These cost money per message.</b> A deposit or a withdrawal is one
 * SMS; a transfer between two customers of this cell is TWO (sender debited,
 * recipient credited). Budget accordingly before enabling a high-volume cell,
 * and watch {@code innbucks.transaction.notifications}.
 */
@ConfigurationProperties(prefix = "innbucks.notify.transactions")
public record TransactionNotificationProperties(

        /** Master switch. Off = no transaction SMS at all (the ledger and statement are unaffected). */
        boolean enabled,

        /**
         * Also alert the initiating customer when a movement POSITIVELY
         * failed. Default off: the app already renders the failure inline, a
         * core outage would then SMS every customer who tried, and "your
         * transfer failed" is not news to someone staring at the error screen.
         * Turn on where the cell's support desk prefers the paper trail.
         *
         * <p>Never sent to the RECIPIENT of a failed transfer — nothing
         * arrived, so there is nothing to tell them.
         */
        boolean notifyOnFailure,

        /**
         * Time zone for the timestamp rendered in the message. Blank = the
         * deployment country's civil zone ({@code Country.zoneId()}), which is
         * the right answer for every single-country cell. Storage and logs
         * stay UTC regardless.
         */
        String zone,

        /**
         * Character budget for one message. The narration clause is dropped
         * (message still sent, just without it) rather than let a chatty
         * reference push every transfer alert into a second billed segment.
         * 160 = one GSM-7 segment.
         */
        int maxLength,

        /** Narration is truncated to this many characters before the budget check. */
        int maxNarrativeLength
) {
}
