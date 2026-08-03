package zw.co.innbucks.middleware.anomaly;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches the brute-force shape that every other control in this service is
 * blind to: <b>one source trying a few PINs against very many different
 * accounts</b>.
 *
 * <p>The existing defences are all scoped to a single victim or a single
 * address, and a spray defeats both by construction. Per-account lockout fires
 * at seven failures — so an attacker simply stops at five and moves to the next
 * number, and no account ever locks. The per-IP bucket allows fifteen logins a
 * minute — which is 21,600 attempts a day from one address, every one of them
 * "under the limit". Five attempts each against ten thousand MSISDNs is,
 * today, completely invisible: no lock, no 429, nothing but a scattering of
 * ordinary-looking failures. This class is what sees it.
 *
 * <p><b>The signal is distinct accounts per source, never attempt count</b> —
 * see {@link AuthAnomalyProperties} for why that distinction is what makes
 * automatic blocking safe behind NAT.
 *
 * <p><b>Nothing here may break authentication.</b> Every entry point is
 * wrapped: a detector that throws would turn a monitoring bug into a total
 * sign-in outage, which is a far worse failure than the attack it watches for.
 * It fails open, loudly.
 *
 * <p>Accounts are held as hashes rather than MSISDNs. The value is ephemeral
 * and never persisted, so this is not the keyed-HMAC case the storage rules
 * cover — it simply keeps a heap dump from yielding a tidy list of which
 * customers an attacker was working through.
 *
 * <p>Watch {@code innbucks.auth.failures} for the rate (Prometheus alerts on
 * the spike; see {@code deploy/prometheus/auth-anomaly-alerts.yml}) and
 * {@code innbucks.auth.spray.detected} for confirmed sprays.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AuthAnomalyProperties.class)
public class AuthAnomalyDetector {

    private final AuthAnomalyProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** source address -> the accounts it has failed against this window. */
    private final Cache<String, SourceWindow> sources;

    /** source address -> instant the block lifts. */
    private final Cache<String, Instant> blocked;

    public AuthAnomalyDetector(AuthAnomalyProperties properties,
                               ClientIpResolver clientIpResolver,
                               AuditService auditService,
                               MeterRegistry meterRegistry,
                               Clock clock) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.sources = Caffeine.newBuilder()
                // Two windows of slack so a source that goes quiet is forgotten
                // rather than accumulating across unrelated bursts.
                .expireAfterAccess(properties.window().multipliedBy(2))
                .maximumSize(Math.max(1, properties.maxTrackedSources()))
                .build();
        this.blocked = Caffeine.newBuilder()
                .expireAfterWrite(properties.blockDuration())
                .maximumSize(Math.max(1, properties.maxTrackedSources()))
                .build();
        Gauge.builder("innbucks.auth.spray.blocked_sources", blocked, Cache::estimatedSize)
                .description("Source addresses currently blocked from the auth endpoints for credential spraying")
                .register(meterRegistry);
    }

    @PostConstruct
    void announce() {
        if (!properties.enabled()) {
            log.warn("Credential-spray detection is DISABLED — a source trying a few PINs across many "
                    + "accounts will not be detected or blocked.");
            return;
        }
        log.info("Credential-spray detection active: alert at {} distinct accounts per source per {}, "
                        + "{} at {} (block {}).",
                properties.distinctSubjectsAlert(), properties.window(),
                properties.blockEnabled() ? "block" : "observe-only (blocking off)",
                properties.distinctSubjectsBlock(), properties.blockDuration());
    }

    /**
     * Report one failed authentication. {@code subject} is the account the
     * attempt targeted — the normalised MSISDN — regardless of whether that
     * account exists, because attempts against numbers that are NOT registered
     * are exactly the enumeration half of the attack.
     *
     * <p>Never throws.
     */
    public void recordFailure(AuthFailureKind kind, String subject) {
        // The counter is emitted even when tracking is off: it is the raw signal
        // Prometheus alerts on, and it costs nothing.
        failureCounter(kind).increment();
        if (!properties.enabled() || subject == null || subject.isBlank()) {
            return;
        }
        try {
            HttpServletRequest request = currentRequest();
            if (request == null) {
                return;
            }
            track(clientIpResolver.resolve(request), kind, hash(subject));
        } catch (RuntimeException ex) {
            // Fail open: watching sign-ins must never be able to stop them.
            log.warn("Credential-spray detection skipped for a {} failure: {}", kind, ex.toString());
        }
    }

    /** True while this source is serving out a spray block. Never throws. */
    public boolean isBlocked(String clientIp) {
        if (!properties.enabled() || !properties.blockEnabled() || clientIp == null) {
            return false;
        }
        try {
            return remainingBlockSeconds(clientIp) > 0;
        } catch (RuntimeException ex) {
            log.warn("Spray-block lookup failed, allowing the request: {}", ex.toString());
            return false;
        }
    }

    /** Seconds until the block lifts, or 0 when the source is not blocked. */
    public long remainingBlockSeconds(String clientIp) {
        Instant until = blocked.getIfPresent(clientIp);
        if (until == null) {
            return 0L;
        }
        long seconds = Duration.between(clock.instant(), until).getSeconds();
        if (seconds <= 0) {
            blocked.invalidate(clientIp);
            return 0L;
        }
        return seconds;
    }

    private void track(String source, AuthFailureKind kind, String subjectHash) {
        Instant now = clock.instant();
        SourceWindow window = sources.get(source, key -> new SourceWindow(now));
        int distinct;
        boolean crossedAlert = false;
        boolean crossedBlock = false;

        // Per-source lock: contention is confined to a single attacking address,
        // and honest traffic never contends at all.
        synchronized (window) {
            if (Duration.between(window.windowStart, now).compareTo(properties.window()) > 0) {
                window.reset(now);
            }
            if (window.subjects.size() < properties.maxSubjectsPerSource()) {
                window.subjects.add(subjectHash);
            }
            distinct = window.subjects.size();

            if (!window.alerted && distinct >= properties.distinctSubjectsAlert()) {
                window.alerted = true;
                crossedAlert = true;
            }
            if (properties.blockEnabled() && !window.blocked
                    && distinct >= properties.distinctSubjectsBlock()) {
                window.blocked = true;
                crossedBlock = true;
                blocked.put(source, now.plus(properties.blockDuration()));
            }
        }

        // Fire ONCE per source per window, outside the lock. Auditing every
        // failure would serialise the whole attack on audit_chain_head's row
        // lock — and take legitimate money movements down with it.
        if (crossedAlert) {
            onDetected(source, kind, distinct, false);
        }
        if (crossedBlock) {
            onDetected(source, kind, distinct, true);
        }
    }

    private void onDetected(String source, AuthFailureKind kind, int distinct, boolean blockedNow) {
        Counter.builder("innbucks.auth.spray.detected")
                .description("Credential-spray detections, by failure kind and whether the source was blocked")
                .tag("kind", kind.tag())
                .tag("action", blockedNow ? "blocked" : "observed")
                .register(meterRegistry)
                .increment();

        if (blockedNow) {
            log.error("CREDENTIAL SPRAY: source {} failed {} against {} distinct accounts within {} — "
                            + "blocked from the auth endpoints for {}",
                    source, kind, distinct, properties.window(), properties.blockDuration());
        } else {
            log.warn("Possible credential spray: source {} failed {} against {} distinct accounts within {}",
                    source, kind, distinct, properties.window());
        }

        // One tamper-evident row per detection. No customerId: the event is
        // about a SOURCE, not a customer — and naming one victim of a spray
        // would misrepresent it.
        try {
            auditService.record(AuditAction.CREDENTIAL_SPRAY_DETECTED, AuditOutcome.FAILURE, null, null,
                    Map.of("source", source,
                            "kind", kind.name(),
                            "distinctAccounts", distinct,
                            "blocked", blockedNow));
        } catch (RuntimeException ex) {
            log.warn("Could not audit the spray detection for source {}: {}", source, ex.toString());
        }
    }

    private Counter failureCounter(AuthFailureKind kind) {
        return Counter.builder("innbucks.auth.failures")
                .description("Failed authentication attempts, by kind — the rate signal for spike alerting")
                .tag("kind", kind.tag())
                .register(meterRegistry);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String hash(String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest(subject.getBytes(StandardCharsets.UTF_8));
            // 12 bytes is ample: this only has to distinguish accounts within one
            // source's window, and collisions would merely undercount.
            byte[] truncated = new byte[12];
            System.arraycopy(full, 0, truncated, 0, truncated.length);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** One source's tumbling window. Guarded by its own monitor. */
    private static final class SourceWindow {

        private final Set<String> subjects = ConcurrentHashMap.newKeySet();
        private Instant windowStart;
        private boolean alerted;
        private boolean blocked;

        private SourceWindow(Instant windowStart) {
            this.windowStart = windowStart;
        }

        private void reset(Instant now) {
            subjects.clear();
            windowStart = now;
            alerted = false;
            blocked = false;
        }
    }
}
