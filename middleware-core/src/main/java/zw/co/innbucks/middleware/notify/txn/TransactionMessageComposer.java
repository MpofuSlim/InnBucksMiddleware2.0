package zw.co.innbucks.middleware.notify.txn;

import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.notify.SmsTextSanitizer;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the customer-facing text for one {@link MovementLeg}. Pure — no
 * clock, no I/O, no Spring — so every wording and budget rule is unit-tested
 * without a context.
 *
 * <p>Two constraints shape the copy, and both are load-bearing:
 *
 * <ul>
 *   <li><b>The gateway's charset.</b> The InnBucks SMS gateway rejects
 *       {@code ! : / ? " * ;} with {@code 400 "Invalid message"} — which is
 *       why timestamps read {@code 14.05} and labels read {@code Ref.}
 *       rather than the {@code 14:05} / {@code Ref:} the samples used. The
 *       customer-supplied narration additionally goes through
 *       {@link SmsTextSanitizer} here, so the length computed below is the
 *       length that actually ships.</li>
 *   <li><b>One segment.</b> GSM-7 bills per 160 characters. The base message
 *       fits; a long narration would not, so the narration clause is DROPPED
 *       (never the balance, never the reference) when it would push past the
 *       budget. A shorter alert beats a silently doubled SMS bill.</li>
 * </ul>
 */
public final class TransactionMessageComposer {

    private static final String BRAND = "InnBucks.";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy 'at' HH.mm", Locale.ENGLISH);

    private final ZoneId zone;
    private final int maxLength;
    private final int maxNarrativeLength;

    public TransactionMessageComposer(ZoneId zone, int maxLength, int maxNarrativeLength) {
        this.zone = zone;
        this.maxLength = maxLength;
        this.maxNarrativeLength = maxNarrativeLength;
    }

    /**
     * @param balance the account's available balance AFTER the movement, or
     *                null when the core read failed — the alert still goes
     *                out, just without the balance line. Telling a customer
     *                money moved is the point; the balance is a courtesy.
     */
    public String compose(SettledMovementEvent event, MovementLeg leg, MinorUnits balance) {
        return event.completed() ? completed(event, leg, balance) : failed(event, leg);
    }

    private String completed(SettledMovementEvent event, MovementLeg leg, MinorUnits balance) {
        String account = AccountMasking.maskAccount(leg.accountId());
        String amount = money(event.amountMinor(), event.currency());
        String when = WHEN.format(event.occurredAt().atZone(zone));

        String core;
        if (leg.direction() == TransactionDirection.CREDIT) {
            core = leg.counterparty() == null
                    ? "Account ending %s credited with %s on %s.".formatted(account, amount, when)
                    : "Account ending %s credited with %s from account ending %s on %s."
                            .formatted(account, amount, AccountMasking.maskAccount(leg.counterparty()), when);
        } else {
            core = leg.counterparty() == null
                    ? "Account ending %s debited with %s on %s.".formatted(account, amount, when)
                    : "Transfer of %s sent from account ending %s to account ending %s on %s."
                            .formatted(amount, account, AccountMasking.maskAccount(leg.counterparty()), when);
        }

        String base = "%s %s Ref. %s.".formatted(BRAND, core, event.customerFacingRef());
        if (balance != null) {
            base += " Available balance %s.".formatted(money(balance.amount(), balance.currencyCode()));
        }
        return withNarrationIfItFits(base, event.narrative());
    }

    /**
     * Failure copy. Deliberately blunt about the one thing a customer needs to
     * know — that nothing moved — because this is only ever sent for a
     * POSITIVELY failed movement. An ambiguous outcome is parked as UNKNOWN
     * and never reaches this class.
     */
    private String failed(SettledMovementEvent event, MovementLeg leg) {
        String account = AccountMasking.maskAccount(leg.accountId());
        String amount = money(event.amountMinor(), event.currency());
        String when = WHEN.format(event.occurredAt().atZone(zone));

        String core = switch (event.type()) {
            case DEPOSIT -> "Deposit of %s to account ending %s".formatted(amount, account);
            case WITHDRAWAL -> "Withdrawal of %s from account ending %s".formatted(amount, account);
            case TRANSFER -> "Transfer of %s from account ending %s to account ending %s"
                    .formatted(amount, account, AccountMasking.maskAccount(leg.counterparty()));
        };
        return "%s %s on %s was NOT successful. Ref. %s. No funds have moved."
                .formatted(BRAND, core, when, event.customerFacingRef());
    }

    /**
     * Copy for a movement the CORE reported (teller/admin posting, interest,
     * correction). Same template family as the credit/debit wording above so a
     * customer sees ONE consistent voice regardless of which door the money
     * came through; no balance line, because the observing path has no
     * settled-balance read to quote.
     */
    public String composeObserved(zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved movement) {
        String account = AccountMasking.maskAccount(movement.accountExternalId());
        String amount = money(movement.amount().amount(), movement.amount().currencyCode());
        String when = WHEN.format(movement.occurredAt().atZone(zone));
        String verb = movement.direction() == TransactionDirection.CREDIT ? "credited" : "debited";
        String ref = movement.coreTxRef() == null || movement.coreTxRef().isBlank()
                ? null : movement.coreTxRef().trim();

        String base = "%s Account ending %s %s with %s on %s.".formatted(BRAND, account, verb, amount, when);
        if (ref != null) {
            base += " Ref. %s.".formatted(ref);
        }
        return withNarrationIfItFits(base, movement.narrative());
    }

    private String withNarrationIfItFits(String base, String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return base;
        }
        String clean = SmsTextSanitizer.toGsmSafe(narrative).trim();
        if (clean.length() > maxNarrativeLength) {
            clean = clean.substring(0, maxNarrativeLength).trim();
        }
        if (clean.isEmpty()) {
            return base;
        }
        String withNarration = base + " Narration - %s.".formatted(clean);
        return withNarration.length() <= maxLength ? withNarration : base;
    }

    /** {@code USD 1,234.56} — grouping from Locale.ROOT so a JVM locale can never reshape a money figure. */
    private static String money(long minor, String currencyCode) {
        BigDecimal major = new MinorUnits(minor, currencyCode).toMajor();
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setMinimumFractionDigits(major.scale());
        format.setMaximumFractionDigits(major.scale());
        return currencyCode + " " + format.format(major);
    }
}
