package zw.co.innbucks.middleware.statement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ceilings for inline statement generation.
 *
 * <p>Statements render ON the request thread of the single-replica money
 * path, so both knobs are protection for transfers, not product limits: a
 * multi-year statement is a large core read plus a memory-hungry PDF render,
 * and one greedy request must not cost a customer their deposit. A period the
 * ceilings refuse is answered with a clear error telling the app to narrow
 * the range — the async render-and-fetch design is the deliberate NEXT slice,
 * not something to fake here by raising these.
 */
@ConfigurationProperties(prefix = "innbucks.statement")
public record StatementProperties(

        /** Longest period one statement may cover, in days (inclusive). */
        int maxPeriodDays,

        /** Most entries one statement may render inline. */
        int maxEntries,

        /** Page size used against the core while collecting the period. */
        int pageSize
) {

    /**
     * Normalises unset values so a context that binds nothing gets working
     * ceilings instead of a zero cap that refuses every statement.
     */
    public StatementProperties {
        maxPeriodDays = maxPeriodDays <= 0 ? 92 : maxPeriodDays;
        maxEntries = maxEntries <= 0 ? 1_000 : maxEntries;
        pageSize = pageSize <= 0 ? 100 : Math.min(pageSize, 100);
    }
}
