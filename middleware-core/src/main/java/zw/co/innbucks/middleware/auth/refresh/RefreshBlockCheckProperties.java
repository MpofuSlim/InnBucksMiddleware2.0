package zw.co.innbucks.middleware.auth.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounds on the core read {@link AccountBlockSessionGuard} makes on every
 * session refresh. Both exist so a slow core can never make refresh slow, and
 * so this check can never become a meaningful share of the core's load.
 */
@ConfigurationProperties(prefix = "innbucks.auth.refresh-block-check")
public record RefreshBlockCheckProperties(

        /**
         * The longest a refresh waits for the core's answer before letting the
         * session through (fail open, {@code outcome=timed_out}). A healthy
         * account listing answers in a few hundred ms; this is a ceiling on a
         * sick one, far below a mobile client's own HTTP timeout, so a client
         * is never left waiting long enough to give up on its refresh.
         */
        Duration timeout,

        /**
         * Reads allowed in flight at once. There is no queue: a refresh that
         * finds every slot busy (a stalled core holds them) skips the check
         * ({@code outcome=busy}). Sized for the refresh rate of a cell, not
         * for a stall — too small only means some checks are skipped, never an
         * outage.
         */
        int maxConcurrentReads
) {

    /**
     * Unset or non-positive values take the defaults, so a profile or a test
     * context that binds nothing still gets a bounded check — never an
     * unbounded wait or a pool that refuses every read.
     */
    public RefreshBlockCheckProperties {
        timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(2) : timeout;
        maxConcurrentReads = maxConcurrentReads <= 0 ? 8 : maxConcurrentReads;
    }
}
