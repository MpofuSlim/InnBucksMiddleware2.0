package zw.co.innbucks.middleware.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The name cache in front of the core's profile read.
 *
 * <p><b>Why a cache is safe HERE and nowhere else on the port.</b> The cached
 * value is a display name, and nothing in this service makes a decision on it:
 * ownership comes from the core's live account list, balances from the live
 * balance read, status from the local row. The one thing a name touches is what
 * a human reads on a screen or in an SMS. That is the entire reason this is a
 * narrow name resolver rather than a caching decorator around
 * {@code CoreBankingPort} — a decorator would sit in front of money, and no TTL
 * makes that safe.
 *
 * <p><b>Staleness is bounded by {@link #ttl} and nothing else.</b> This
 * middleware never writes a customer's name after registration; the only writer
 * is a teller or admin editing the record in the core, which we cannot observe.
 * So TTL is the complete invalidation story, and the blast radius of a stale
 * entry is "the app shows the old name for up to {@code ttl}". A registration
 * cannot be affected: a brand-new customer has no entry, and failures are never
 * cached, so nothing can pin a miss.
 *
 * <p><b>If a name-WRITE path is ever added to this middleware, it must
 * invalidate the entry</b> — otherwise a customer would change their own name
 * and be shown the old one for a full TTL, which is the kind of bug that reads
 * as data loss.
 *
 * <p>In-memory and therefore <b>per instance</b>, like the rate-limit buckets
 * and the spray detector. Correct for the single-container-per-cell deployment;
 * a second replica simply keeps its own copy (each bounded by the same TTL), so
 * it degrades to a lower hit rate rather than to incorrect behaviour.
 */
@ConfigurationProperties(prefix = "innbucks.core.profile-cache")
public record ProfileCacheProperties(

        /**
         * Master switch. Off means every consumer goes to the core exactly as
         * before — the escape hatch for a cell that wants zero staleness, or
         * for isolating the cache while debugging a wrong-name report.
         */
        boolean enabled,

        /**
         * How long a name may be served without re-reading the core. Also the
         * worst-case delay before a teller's correction reaches the app, so
         * this is a staleness budget, not just a performance knob.
         */
        Duration ttl,

        /**
         * Cap on cached customers. Bounds memory on a cell with a large book;
         * past the cap Caffeine evicts and those customers simply pay the core
         * read again.
         */
        long maximumSize
) {

    /**
     * Normalises unset values so a profile that binds only {@code enabled} (or
     * a test context that binds nothing) cannot build a cache with a zero TTL
     * — which would silently disable it while reporting as enabled — or an
     * unbounded one.
     */
    public ProfileCacheProperties {
        ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(5) : ttl;
        maximumSize = maximumSize <= 0 ? 50_000 : maximumSize;
    }
}
