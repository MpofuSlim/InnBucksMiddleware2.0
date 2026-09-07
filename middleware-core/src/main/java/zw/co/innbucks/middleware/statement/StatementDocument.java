package zw.co.innbucks.middleware.statement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * A complete account statement for a fixed period — the renderer-neutral
 * model every output format (JSON, PDF, CSV) is produced from, so the three
 * can never disagree about a number.
 *
 * <p>This is deliberately a value object with no Spring in it: assembly
 * (balance anchoring, totals) happens once in {@link StatementAssembler}, and
 * a renderer only ever formats what is already here. A renderer that computed
 * anything would be a second implementation of statement semantics waiting to
 * drift.
 *
 * <p>Balances are SIGNED minor units — see {@link StatementLine} for why.
 * Amount totals are non-negative by construction and count non-reversed
 * entries only. {@code opening + totalCredits - totalDebits == closing} holds
 * whenever the core's own running balances agree with its amounts; where they
 * do not, the assembler adopts the core's balance (the core is the book of
 * record) and reports the disagreement to the caller instead of hiding it.
 *
 * @param customerName best-effort display name from the core; null when the
 *                     core has no profile (the statement still renders — the
 *                     MSISDN identifies the holder)
 * @param generatedAt  the instant this document was assembled; rendered in
 *                     {@code displayZone} (the deployment country's civil
 *                     zone — storage and logs stay UTC, per the house rule)
 */
public record StatementDocument(
        String accountId,
        String currencyCode,
        String customerName,
        String msisdn,
        LocalDate from,
        LocalDate to,
        Instant generatedAt,
        ZoneId displayZone,
        long openingBalanceMinor,
        long closingBalanceMinor,
        long totalCreditsMinor,
        long totalDebitsMinor,
        List<StatementLine> lines
) {

    public StatementDocument {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(msisdn, "msisdn");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(displayZone, "displayZone");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (totalCreditsMinor < 0 || totalDebitsMinor < 0) {
            throw new IllegalArgumentException("totals must be >= 0");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' (" + to + ") is before 'from' (" + from + ")");
        }
    }
}
