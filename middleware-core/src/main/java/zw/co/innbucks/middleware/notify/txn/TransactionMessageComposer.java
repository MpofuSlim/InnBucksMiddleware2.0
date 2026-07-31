package zw.co.innbucks.middleware.notify.txn;

import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
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
 * Builds the customer-facing text for one movement. Pure — no clock, no I/O,
 * no Spring — so every wording and budget rule is unit-tested without a
 * context.
 *
 * <p>The voice is the owner's, set after reading the first live alerts:
 * messages open with <b>"Your account ending 0010"</b> (no brand prefix — the
 * SMS sender identity already says who it's from), account tails are the last
 * four digits of the core's CUSTOMER-FACING account number (never the hex tail
 * of an internal id), and references render zero-padded ({@code Ref 00000021})
 * so a raw core id reads like a reference while staying matchable to the
 * core's records.
 *
 * <p>Two mechanical constraints shape everything, and both are load-bearing:
 *
 * <ul>
 *   <li><b>The gateway's charset.</b> The InnBucks SMS gateway rejects
 *       {@code ! : / ? " * ;} with {@code 400 "Invalid message"} — which is
 *       why timestamps read {@code 14.05} and never {@code 14:05}. Narrations
 *       additionally pass {@link SmsTextSanitizer}, so the length computed
 *       here is the length that ships.</li>
 *   <li><b>One segment.</b> GSM-7 bills per 160 characters. The narration
 *       clause is DROPPED (never the balance, never the reference) when it
 *       would push past the budget.</li>
 * </ul>
 */
public final class TransactionMessageComposer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy 'at' HH.mm", Locale.ENGLISH);
    private static final int REF_PAD_WIDTH = 8;
    private static final int TAIL_DIGITS = 4;

    private final ZoneId zone;
    private final int maxLength;
    private final int maxNarrativeLength;

    public TransactionMessageComposer(ZoneId zone, int maxLength, int maxNarrativeLength) {
        this.zone = zone;
        this.maxLength = maxLength;
        this.maxNarrativeLength = maxNarrativeLength;
    }

    /**
     * @param ownBalance the LEG's account balance after the movement, or null
     *        when the read failed — the alert still goes out, just without the
     *        balance line. Also the source of the numeric account tail.
     * @param counterpartyAccountNumber the other account's customer-facing
     *        number when known, or null — falls back to the externalId tail.
     */
    public String compose(SettledMovementEvent event, MovementLeg leg,
                          AccountBalance ownBalance, String counterpartyAccountNumber) {
        return event.completed()
                ? completed(event, leg, ownBalance, counterpartyAccountNumber)
                : failed(event, leg, counterpartyAccountNumber);
    }

    private String completed(SettledMovementEvent event, MovementLeg leg,
                             AccountBalance ownBalance, String counterpartyAccountNumber) {
        String own = tail(ownBalance == null ? null : ownBalance.accountNumber(), leg.accountId());
        String amount = money(event.amountMinor(), event.currency());
        String when = WHEN.format(event.occurredAt().atZone(zone));

        String core;
        if (leg.direction() == TransactionDirection.CREDIT) {
            core = leg.counterparty() == null
                    ? "Your account ending %s has been credited with %s on %s."
                            .formatted(own, amount, when)
                    : "Your account ending %s has been credited with %s from account ending %s on %s."
                            .formatted(own, amount, tail(counterpartyAccountNumber, leg.counterparty()), when);
        } else {
            core = leg.counterparty() == null
                    ? "Your account ending %s has been debited with %s on %s."
                            .formatted(own, amount, when)
                    : "You sent %s from your account ending %s to account ending %s on %s."
                            .formatted(amount, own, tail(counterpartyAccountNumber, leg.counterparty()), when);
        }

        String base = core + " Ref %s.".formatted(refFor(event.customerFacingRef()));
        if (ownBalance != null) {
            base += " Available balance %s.".formatted(
                    money(ownBalance.available().amount(), ownBalance.available().currencyCode()));
        }
        return withNarrationIfItFits(base, event.narrative());
    }

    /**
     * Failure copy — only ever sent for a POSITIVELY failed movement (an
     * ambiguous outcome is parked as UNKNOWN and never reaches this class).
     * No balance line: nothing moved, so quoting a balance invites "so where
     * did it go" support calls.
     */
    private String failed(SettledMovementEvent event, MovementLeg leg, String counterpartyAccountNumber) {
        String own = tail(null, leg.accountId());
        String amount = money(event.amountMinor(), event.currency());
        String when = WHEN.format(event.occurredAt().atZone(zone));

        String core = switch (event.type()) {
            case DEPOSIT -> "Your deposit of %s to account ending %s on %s".formatted(amount, own, when);
            case WITHDRAWAL -> "Your withdrawal of %s from account ending %s on %s".formatted(amount, own, when);
            case TRANSFER -> "Your transfer of %s from account ending %s to account ending %s on %s"
                    .formatted(amount, own, tail(counterpartyAccountNumber, leg.counterparty()), when);
        };
        return "%s was NOT successful. Ref %s. No funds have moved."
                .formatted(core, refFor(event.customerFacingRef()));
    }

    /**
     * Copy for a movement the CORE reported (teller/admin posting, interest,
     * correction). Same voice as the app-path templates — a customer sees ONE
     * consistent style regardless of which door the money came through.
     */
    public String composeObserved(CoreMovementObserved movement, AccountBalance balance) {
        String own = tail(balance == null ? null : balance.accountNumber(), movement.accountExternalId());
        String amount = money(movement.amount().amount(), movement.amount().currencyCode());
        String when = WHEN.format(movement.occurredAt().atZone(zone));
        String verb = movement.direction() == TransactionDirection.CREDIT ? "credited" : "debited";

        String base = "Your account ending %s has been %s with %s on %s."
                .formatted(own, verb, amount, when);
        String ref = refFor(movement.coreTxRef());
        if (ref != null) {
            base += " Ref %s.".formatted(ref);
        }
        if (balance != null) {
            base += " Available balance %s.".formatted(
                    money(balance.available().amount(), balance.available().currencyCode()));
        }
        // No narration clause: the core path has no customer-entered text —
        // what Fineract offers is the transaction TYPE name ("Deposit"),
        // which just restates the verb.
        return base;
    }

    /**
     * The account tail a customer recognises: the last four DIGITS of the
     * core's customer-facing account number ({@code 000000010} → {@code
     * 0010}). Only when no number is available does it fall back to the
     * internal externalId's tail — degraded, but still identifying.
     */
    static String tail(String accountNumber, String externalIdFallback) {
        if (accountNumber != null) {
            String digits = accountNumber.replaceAll("\\D", "");
            if (digits.length() >= TAIL_DIGITS) {
                return digits.substring(digits.length() - TAIL_DIGITS);
            }
            if (!digits.isEmpty()) {
                return digits;
            }
        }
        return AccountMasking.maskAccount(externalIdFallback);
    }

    /**
     * {@code 21} → {@code 00000021}: a bare core id zero-padded to read like a
     * reference while staying matchable to the core's records. Non-numeric
     * refs (our SHA-256 head fallback) keep their first eight characters,
     * uppercased. Null in, null out.
     */
    static String refFor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String ref = raw.trim();
        if (ref.chars().allMatch(Character::isDigit)) {
            return ref.length() >= REF_PAD_WIDTH ? ref
                    : "0".repeat(REF_PAD_WIDTH - ref.length()) + ref;
        }
        return (ref.length() <= REF_PAD_WIDTH ? ref : ref.substring(0, REF_PAD_WIDTH))
                .toUpperCase(Locale.ROOT);
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
