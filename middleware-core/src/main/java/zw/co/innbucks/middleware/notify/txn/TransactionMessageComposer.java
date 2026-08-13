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
    /** Length of {@code "account ending 0000"} — the phrase a name replaces. */
    private static final int MAX_PARTY_NAME = 19;

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
     * @param counterpartyName the other PARTY rendered as {@code T.Mpofu}, or
     *        null to identify them by account instead. A name is friendlier
     *        than four digits when the other side is a person the customer
     *        actually transacted with, and {@link #partyName} keeps it no
     *        longer than the account phrase it replaces, so naming can never
     *        cost a second SMS segment.
     */
    public String compose(SettledMovementEvent event, MovementLeg leg, AccountBalance ownBalance,
                          String counterpartyAccountNumber, String counterpartyName) {
        return event.completed()
                ? completed(event, leg, ownBalance, counterpartyAccountNumber, counterpartyName)
                : failed(event, leg, counterpartyAccountNumber);
    }

    private String completed(SettledMovementEvent event, MovementLeg leg, AccountBalance ownBalance,
                             String counterpartyAccountNumber, String counterpartyName) {
        String own = tail(ownBalance == null ? null : ownBalance.accountNumber(), leg.accountId());
        String amount = money(event.amountMinor(), event.currency());
        String when = WHEN.format(event.occurredAt().atZone(zone));
        String other = counterparty(counterpartyName, counterpartyAccountNumber, leg.counterparty());

        String core;
        if (leg.direction() == TransactionDirection.CREDIT) {
            core = leg.counterparty() == null
                    ? "Your account ending %s has been credited with %s on %s."
                            .formatted(own, amount, when)
                    : "Your account ending %s has been credited with %s from %s on %s."
                            .formatted(own, amount, other, when);
        } else {
            core = leg.counterparty() == null
                    ? "Your account ending %s has been debited with %s on %s."
                            .formatted(own, amount, when)
                    : "You sent %s from your account ending %s to %s on %s."
                            .formatted(amount, own, other, when);
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
     * How the other side of a movement is identified in the sentence: their
     * NAME when we could resolve it, otherwise the account phrase this has
     * always used.
     */
    private static String counterparty(String name, String accountNumber, String externalIdFallback) {
        return name != null && !name.isBlank()
                ? name.trim()
                : "account ending " + tail(accountNumber, externalIdFallback);
    }

    /**
     * {@code ("Tariro","Mpofu")} → {@code T.Mpofu} — the sender as the
     * recipient of their money should see them.
     *
     * <p>Note this is the MIRROR of the masking on {@code POST /accounts/lookup}
     * ("Tariro M."), and deliberately so. That endpoint is an oracle anyone can
     * query by phone number, so it discloses the minimum needed to catch a
     * typo. This one only ever reaches someone who has actually been paid, and
     * for them the surname is the useful half — it is how you know which Tariro
     * sent you money.
     *
     * <p>Returns null when there is no usable name, which falls the sentence
     * back to the account phrase. The result is sanitised for the SMS gateway's
     * charset and capped at {@link #MAX_PARTY_NAME} — chosen to be no longer
     * than {@code "account ending 0000"}, so a named message is never longer
     * than the numbered one it replaces and the segment budget cannot regress.
     */
    static String partyName(String firstName, String lastName) {
        String first = firstName == null ? "" : SmsTextSanitizer.toGsmSafe(firstName).trim();
        String last = lastName == null ? "" : SmsTextSanitizer.toGsmSafe(lastName).trim();
        if (last.isEmpty()) {
            // Without a surname there is nothing to initial; a bare first name
            // identifies nobody usefully, so let the account phrase speak.
            return null;
        }
        String rendered = first.isEmpty()
                ? last
                : first.substring(0, 1).toUpperCase(Locale.ROOT) + "." + last;
        return rendered.length() <= MAX_PARTY_NAME ? rendered : rendered.substring(0, MAX_PARTY_NAME);
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
