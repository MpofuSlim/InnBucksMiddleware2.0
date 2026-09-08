package zw.co.innbucks.middleware.statement.web;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.statement.StatementProperties;

import java.time.Duration;

/**
 * Per-source throttle for the console statement endpoint — which forwards
 * whatever Basic credential it is handed to Fineract for verification, and
 * would otherwise be a convenient operator-password oracle: an attacker could
 * spray credentials through us at whatever rate we accept. The default 30/min
 * per source is generous for humans printing statements and useless for a
 * spray; a busy branch behind one NAT can raise it per cell
 * ({@code innbucks.statement.console-requests-per-minute}) — the keying stays
 * per-ADDRESS regardless, because a per-operator budget would let a spray
 * widen itself by rotating usernames.
 *
 * <p>In-memory and per-instance like every other limiter here (single
 * replica per cell); Caffeine-bounded so a spray of forged source addresses
 * costs memory only up to the cap. Deliberately NOT wired into
 * {@code RateLimitProperties}' fixed bucket set — this endpoint is not an
 * auth endpoint and its budget has no reason to move with theirs.
 */
@Component
public class ConsoleStatementRateLimiter {

    private final LoadingCache<String, Bucket> buckets;

    public ConsoleStatementRateLimiter(StatementProperties properties) {
        int perMinute = properties.consoleRequestsPerMinute();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build(source -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(perMinute)
                                .refillGreedy(perMinute, Duration.ofMinutes(1))
                                .build())
                        .build());
    }

    /** True if this source may proceed; false = answer 429. */
    public boolean tryConsume(String source) {
        return buckets.get(source).tryConsume(1);
    }
}
