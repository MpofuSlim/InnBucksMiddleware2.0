package zw.co.innbucks.middleware.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Thresholds for credential-spray detection.
 *
 * <p><b>Why the thresholds count DISTINCT ACCOUNTS and not attempts.</b> Total
 * failures from one address is a terrible signal behind NAT — an office, a
 * campus or a carrier CGNAT range legitimately produces a stream of fat-fingered
 * PINs from a single public IP, and we have already seen this deployment serve a
 * whole office from one address. The number of DIFFERENT accounts a single
 * source fails against, though, is a signal honest traffic essentially never
 * produces: twenty colleagues mistyping their own PINs is twenty failures across
 * (at most) twenty accounts spread over a day, not thirty distinct accounts
 * inside fifteen minutes. That asymmetry is what makes automatic blocking safe
 * enough to enable by default.
 *
 * <p>In-memory and therefore <b>per instance</b>, exactly like the rate-limit
 * buckets. Correct for the single-container-per-cell deployment; a second
 * replica halves the effective sensitivity (each sees only its own share of the
 * traffic) and would need a shared store.
 */
@ConfigurationProperties(prefix = "innbucks.security.anomaly")
public record AuthAnomalyProperties(

        /** Master switch for tracking + blocking. Failure COUNTERS are emitted either way. */
        boolean enabled,

        /**
         * Rolling (tumbling) observation window. A source's distinct-account set
         * is cleared when the window rolls, so a slow attacker can straddle the
         * boundary and effectively get up to 2x the threshold — acceptable for a
         * detector whose job is catching bursts, not proving a bound.
         */
        Duration window,

        /** Distinct accounts failed from one source within the window before we alert. */
        int distinctSubjectsAlert,

        /** Distinct accounts before the source is temporarily blocked. Must be >= the alert level. */
        int distinctSubjectsBlock,

        /** How long a blocked source stays blocked from the AUTH endpoints. */
        Duration blockDuration,

        /**
         * Whether crossing {@link #distinctSubjectsBlock} actually blocks, or only
         * alerts. Turn off to run the detector in observe-only mode while tuning
         * thresholds against real traffic.
         */
        boolean blockEnabled,

        /**
         * Cap on tracked sources. The detector must not become the memory-exhaustion
         * vector it defends against: an attacker rotating addresses would otherwise
         * mint an unbounded number of tracking entries.
         */
        int maxTrackedSources,

        /**
         * Cap on distinct accounts remembered per source. Past the block threshold the
         * exact count stops mattering, so there is no reason to keep growing the set.
         */
        int maxSubjectsPerSource
) {

    /**
     * Normalises unset values so a profile that binds only {@code enabled} (or
     * a test context that binds nothing) cannot NPE the detector's constructor
     * or, worse, produce a zero threshold that blocks the first caller to
     * mistype a PIN. The block level is also floored at the alert level: a
     * config with block &lt; alert would fire both at once and read as a bug.
     */
    public AuthAnomalyProperties {
        window = window == null ? Duration.ofMinutes(15) : window;
        blockDuration = blockDuration == null ? Duration.ofMinutes(15) : blockDuration;
        distinctSubjectsAlert = distinctSubjectsAlert <= 0 ? 10 : distinctSubjectsAlert;
        distinctSubjectsBlock = Math.max(
                distinctSubjectsBlock <= 0 ? 30 : distinctSubjectsBlock, distinctSubjectsAlert);
        maxTrackedSources = maxTrackedSources <= 0 ? 50_000 : maxTrackedSources;
        maxSubjectsPerSource = maxSubjectsPerSource <= 0 ? 1_000 : maxSubjectsPerSource;
    }
}
