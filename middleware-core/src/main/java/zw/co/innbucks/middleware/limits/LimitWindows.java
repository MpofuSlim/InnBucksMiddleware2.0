package zw.co.innbucks.middleware.limits;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * The calendar day and calendar month that contain one instant, in the
 * market's civil time zone ({@code Country.zoneId()}, Africa/Harare for ZW),
 * as half-open UTC intervals {@code [start, end)}.
 *
 * <p>Computed in Java and compared against {@code ledger_transaction.created_at}
 * (a {@code timestamptz} stamped from the UTC clock), so the SQL never does
 * zone arithmetic and the database's own TimeZone setting cannot move a
 * boundary. Built from {@link LocalDate} + {@code atStartOfDay(zone)}, which is
 * correct across a DST change too (no supported market has one today).
 *
 * <p>A day is always inside its month, so {@code [monthStart, monthEnd)} is the
 * one range the usage query scans.
 */
public record LimitWindows(ZoneId zone, Instant dayStart, Instant dayEnd,
                           Instant monthStart, Instant monthEnd) {

    public LimitWindows {
        Objects.requireNonNull(zone, "zone");
    }

    public static LimitWindows at(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return new LimitWindows(zone,
                today.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant(),
                firstOfMonth.atStartOfDay(zone).toInstant(),
                firstOfMonth.plusMonths(1).atStartOfDay(zone).toInstant());
    }

    /** When the daily allowances (money and count) come back, e.g. {@code 2026-10-29T00:00:00+02:00}. */
    public String dayResetsAt() {
        return render(dayEnd);
    }

    /** When the monthly allowance comes back, e.g. {@code 2026-11-01T00:00:00+02:00}. */
    public String monthResetsAt() {
        return render(monthEnd);
    }

    /**
     * ISO-8601 at the MARKET offset — what the customer's clock reads at that
     * instant. Seconds always present (the formatter, unlike
     * {@code OffsetDateTime.toString()}, never drops {@code :00}).
     */
    public String render(Instant instant) {
        return instant.atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
