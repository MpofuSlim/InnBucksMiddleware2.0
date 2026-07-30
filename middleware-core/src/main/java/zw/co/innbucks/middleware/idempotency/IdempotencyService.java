package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Service-level idempotency for state-changing endpoints. Pattern:
 *
 *   1. Caller supplies an Idempotency-Key header per logical request.
 *   2. Controller wraps its work in idempotencyService.execute(key, body, () -> work).
 *   3. First invocation CLAIMS the key with an INSERT … ON CONFLICT DO NOTHING
 *      row (response_status = 0 sentinel) BEFORE running the work, then stores
 *      the response on the same row. The claim is what closes the concurrent
 *      double-execute race: exactly one caller wins the insert and runs the
 *      work — critical for money movement against cores with no server-side
 *      dedup of their own.
 *   4. Retries with the same key + same body replay the cached response with
 *      its ORIGINAL status; a retry racing the winner gets 409
 *      idempotency_in_progress and re-tries with the same key.
 *   5. Retries with the same key + different body fail with 422 idempotency_conflict.
 *
 * If the work throws, the claim row is deleted so a retry can re-execute. A
 * crash between claim and store leaves a stale claim; claims older than
 * {@link #IN_PROGRESS_GRACE} are treated as dead and taken over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    /** Sentinel response_status meaning "claimed, work in flight". */
    static final int IN_PROGRESS = 0;

    /**
     * A claim older than this whose work never stored a response is presumed
     * crashed (the work either completed upstream or died with the JVM); a new
     * caller may take it over. Must comfortably exceed the slowest upstream
     * write timeout so a live-but-slow call is never usurped.
     */
    static final Duration IN_PROGRESS_GRACE = Duration.ofSeconds(60);

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_record
                (idempotency_key, request_fingerprint, http_method, request_path,
                 response_status, response_body, created_at, expires_at)
            VALUES (?, ?, ?, ?, %d, NULL, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """.formatted(IN_PROGRESS);

    private static final String SELECT_SQL = """
            SELECT request_fingerprint, response_status, response_body, created_at
              FROM idempotency_record
             WHERE idempotency_key = ?
            """;

    private static final String STORE_RESPONSE_SQL = """
            UPDATE idempotency_record
               SET response_status = ?, response_body = ?
             WHERE idempotency_key = ?
            """;

    private static final String RELEASE_CLAIM_SQL = """
            DELETE FROM idempotency_record
             WHERE idempotency_key = ? AND response_status = %d
            """.formatted(IN_PROGRESS);

    private static final String TAKE_OVER_STALE_SQL = """
            DELETE FROM idempotency_record
             WHERE idempotency_key = ? AND response_status = %d AND created_at < ?
            """.formatted(IN_PROGRESS);

    private static final String PRUNE_SQL = """
            DELETE FROM idempotency_record
             WHERE expires_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;
    private final Clock clock;

    /** Backwards-compatible overload: successful work stores HTTP 200. */
    public <T> Result<T> execute(String key,
                                 String httpMethod,
                                 String requestPath,
                                 Object requestBody,
                                 Class<T> responseType,
                                 Supplier<T> work) {
        return execute(key, httpMethod, requestPath, requestBody, responseType, 200, work);
    }

    public <T> Result<T> execute(String key,
                                 String httpMethod,
                                 String requestPath,
                                 Object requestBody,
                                 Class<T> responseType,
                                 int successStatus,
                                 Supplier<T> work) {
        if (successStatus == IN_PROGRESS) {
            throw new IllegalArgumentException("successStatus 0 is reserved for the in-progress sentinel");
        }
        String fingerprint = fingerprint(requestBody);

        // Two passes at most: the second only after evicting a stale claim.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (tryClaim(key, fingerprint, httpMethod, requestPath)) {
                return runAsClaimOwner(key, responseType, successStatus, work);
            }

            CachedEntry entry = lookup(key).orElse(null);
            if (entry == null) {
                // Claim lost AND row gone: the concurrent owner's work threw and
                // released. Loop once more to claim afresh.
                continue;
            }
            if (!entry.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            if (entry.responseStatus() != IN_PROGRESS) {
                log.debug("Idempotency-Key {} -> replaying cached response (status={})",
                        key, entry.responseStatus());
                T cached = deserialize(entry.responseBody(), responseType);
                return new Result<>(cached, entry.responseStatus(), true);
            }
            Instant staleBefore = clock.instant().minus(IN_PROGRESS_GRACE);
            if (entry.createdAt().isBefore(staleBefore)
                    && jdbcTemplate.update(TAKE_OVER_STALE_SQL, key, Timestamp.from(staleBefore)) == 1) {
                log.warn("Idempotency-Key {}: evicted stale in-progress claim from {}; taking over",
                        key, entry.createdAt());
                continue;
            }
            throw new IdempotencyInProgressException(key);
        }
        throw new IdempotencyInProgressException(key);
    }

    private boolean tryClaim(String key, String fingerprint, String httpMethod, String requestPath) {
        Instant now = clock.instant();
        return jdbcTemplate.update(CLAIM_SQL,
                key, fingerprint, httpMethod, requestPath,
                Timestamp.from(now), Timestamp.from(now.plus(properties.ttl()))) == 1;
    }

    private <T> Result<T> runAsClaimOwner(String key, Class<T> responseType,
                                          int successStatus, Supplier<T> work) {
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error ex) {
            // Release so a retry can re-execute. If the work partially applied
            // upstream, the upstream's own dedup / reconciliation is the guard —
            // holding the claim forever would turn one failure into a stuck key.
            try {
                jdbcTemplate.update(RELEASE_CLAIM_SQL, key);
            } catch (Exception releaseEx) {
                log.error("Failed to release idempotency claim for key {}", key, releaseEx);
            }
            throw ex;
        }
        jdbcTemplate.update(STORE_RESPONSE_SQL, successStatus, serialize(result), key);
        return new Result<>(result, successStatus, false);
    }

    /**
     * Daily prune of idempotency_record rows past their {@code expires_at}.
     * Records are pure response caches — once expired a retry re-executes (the
     * TTL contract), so there is no forensic value in keeping them. Runs at
     * 03:35 server-time; single-replica deploy assumption holds (one per
     * country) — wrap with ShedLock if ever scaled horizontally within a market.
     */
    @Scheduled(cron = "${innbucks.idempotency.prune-cron:0 35 3 * * *}")
    public void pruneExpiredIdempotencyRecords() {
        int deleted = jdbcTemplate.update(PRUNE_SQL, Timestamp.from(clock.instant()));
        log.info("Pruned {} expired idempotency_record row(s)", deleted);
    }

    private Optional<CachedEntry> lookup(String key) {
        var rows = jdbcTemplate.query(SELECT_SQL,
                (rs, n) -> new CachedEntry(
                        rs.getString("request_fingerprint"),
                        rs.getInt("response_status"),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toInstant()),
                key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private String fingerprint(Object body) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to fingerprint idempotency request body", ex);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise idempotent response", ex);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialise cached idempotent response", ex);
        }
    }

    private record CachedEntry(String fingerprint, int responseStatus, String responseBody,
                               Instant createdAt) {
    }

    public record Result<T>(T body, int status, boolean replayed) {
    }
}
