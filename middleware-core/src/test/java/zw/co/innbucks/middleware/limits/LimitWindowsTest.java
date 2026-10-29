package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.common.country.Country;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calendar day and month in the MARKET's civil time, and the resetsAt the
 * customer is shown. Harare is UTC+2 with no DST, so a Harare day runs
 * 22:00Z..22:00Z — which is exactly where a UTC-date bug would hide.
 */
class LimitWindowsTest {

    private static final ZoneId HARARE = Country.ZW.zoneId();

    @Test
    void aMiddayPaymentFallsInTheSameCalendarDayAndMonth() {
        LimitWindows w = LimitWindows.at(Instant.parse("2026-10-28T10:00:00Z"), HARARE);

        assertThat(w.dayStart()).isEqualTo(Instant.parse("2026-10-27T22:00:00Z"));
        assertThat(w.dayEnd()).isEqualTo(Instant.parse("2026-10-28T22:00:00Z"));
        assertThat(w.monthStart()).isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(w.monthEnd()).isEqualTo(Instant.parse("2026-10-31T22:00:00Z"));
        assertThat(w.dayResetsAt()).isEqualTo("2026-10-29T00:00:00+02:00");
        assertThat(w.monthResetsAt()).isEqualTo("2026-11-01T00:00:00+02:00");
    }

    /**
     * The 00:00-02:00 Harare seam: 23:30Z on the 28th is 01:30 on the 29th in
     * Harare. For limits it is the 29th (the customer's day) — even though its
     * Fineract value date, stamped from the UTC clock, reads the 28th.
     */
    @Test
    void justAfterHarareMidnightIsTheNewDayAlthoughUtcIsStillTheOldOne() {
        LimitWindows w = LimitWindows.at(Instant.parse("2026-10-28T23:30:00Z"), HARARE);

        assertThat(w.dayStart()).isEqualTo(Instant.parse("2026-10-28T22:00:00Z"));
        assertThat(w.dayResetsAt()).isEqualTo("2026-10-30T00:00:00+02:00");
    }

    @Test
    void theLastMinuteOfTheHarareDayIsStillThatDay() {
        LimitWindows w = LimitWindows.at(Instant.parse("2026-10-28T21:59:59.999Z"), HARARE);
        assertThat(w.dayResetsAt()).isEqualTo("2026-10-29T00:00:00+02:00");
        // ...and the boundary instant itself starts the next one ([start, end)).
        LimitWindows next = LimitWindows.at(Instant.parse("2026-10-28T22:00:00Z"), HARARE);
        assertThat(next.dayStart()).isEqualTo(Instant.parse("2026-10-28T22:00:00Z"));
    }

    @Test
    void monthEndRollsToTheFirst() {
        // 31 Oct, 23:00 Harare.
        LimitWindows w = LimitWindows.at(Instant.parse("2026-10-31T21:00:00Z"), HARARE);
        assertThat(w.dayEnd()).as("the last day ends with its month").isEqualTo(w.monthEnd());
        assertThat(w.monthResetsAt()).isEqualTo("2026-11-01T00:00:00+02:00");
        // 1 Nov, 00:30 Harare — 31 Oct in UTC — is already November's allowance.
        LimitWindows nov = LimitWindows.at(Instant.parse("2026-10-31T22:30:00Z"), HARARE);
        assertThat(nov.monthStart()).isEqualTo(Instant.parse("2026-10-31T22:00:00Z"));
        assertThat(nov.monthResetsAt()).isEqualTo("2026-12-01T00:00:00+02:00");
    }

    @Test
    void decemberRollsIntoJanuaryOfTheNextYear() {
        LimitWindows w = LimitWindows.at(Instant.parse("2026-12-31T20:00:00Z"), HARARE);
        assertThat(w.monthStart()).isEqualTo(Instant.parse("2026-11-30T22:00:00Z"));
        assertThat(w.monthEnd()).isEqualTo(Instant.parse("2026-12-31T22:00:00Z"));
        assertThat(w.dayResetsAt()).isEqualTo("2027-01-01T00:00:00+02:00");
        assertThat(w.monthResetsAt()).isEqualTo("2027-01-01T00:00:00+02:00");

        // New Year's Day 00:15 Harare, still 31 Dec in UTC.
        LimitWindows jan = LimitWindows.at(Instant.parse("2026-12-31T22:15:00Z"), HARARE);
        assertThat(jan.monthResetsAt()).isEqualTo("2027-02-01T00:00:00+02:00");
        assertThat(jan.dayResetsAt()).isEqualTo("2027-01-02T00:00:00+02:00");
    }

    @Test
    void februaryInALeapYear() {
        LimitWindows w = LimitWindows.at(Instant.parse("2028-02-29T10:00:00Z"), HARARE);
        assertThat(w.dayResetsAt()).isEqualTo("2028-03-01T00:00:00+02:00");
        assertThat(w.monthResetsAt()).isEqualTo("2028-03-01T00:00:00+02:00");
    }

    @Test
    void eachMarketRendersItsOwnOffset() {
        LimitWindows nairobi = LimitWindows.at(Instant.parse("2026-10-28T10:00:00Z"), Country.KE.zoneId());
        assertThat(nairobi.dayStart()).isEqualTo(Instant.parse("2026-10-27T21:00:00Z"));
        assertThat(nairobi.dayResetsAt()).isEqualTo("2026-10-29T00:00:00+03:00");
    }
}
