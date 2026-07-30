package zw.co.innbucks.middleware.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainer.class)
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idempotencyService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("TRUNCATE idempotency_record");
    }

    record DummyBody(String foo, int bar) {
    }

    record DummyResponse(String message, int code) {
    }

    @Test
    void firstCallRunsTheWorkAndPersistsTheResult() {
        AtomicInteger calls = new AtomicInteger();
        DummyBody body = new DummyBody("hello", 1);

        var result = idempotencyService.execute(
                "key-1", "POST", "/test", body, DummyResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return new DummyResponse("ok", 42);
                });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body().message()).isEqualTo("ok");
        assertThat(result.body().code()).isEqualTo(42);
    }

    @Test
    void retryWithSameKeyAndSameBodyReplaysCachedResponse() {
        AtomicInteger calls = new AtomicInteger();
        DummyBody body = new DummyBody("hello", 1);

        idempotencyService.execute("key-2", "POST", "/test", body, DummyResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return new DummyResponse("first-run", 42);
                });

        var second = idempotencyService.execute("key-2", "POST", "/test", body, DummyResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return new DummyResponse("should-not-run", 99);
                });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.replayed()).isTrue();
        assertThat(second.body().message()).isEqualTo("first-run");
        assertThat(second.body().code()).isEqualTo(42);
    }

    @Test
    void retryWithSameKeyAndDifferentBodyThrowsIdempotencyConflict() {
        DummyBody first = new DummyBody("hello", 1);
        DummyBody second = new DummyBody("hello", 2);

        idempotencyService.execute("key-3", "POST", "/test", first, DummyResponse.class,
                () -> new DummyResponse("ok", 42));

        assertThatThrownBy(() ->
                idempotencyService.execute("key-3", "POST", "/test", second, DummyResponse.class,
                        () -> new DummyResponse("different", 99)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void failedWorkReleasesTheClaimSoARetryReExecutes() {
        AtomicInteger calls = new AtomicInteger();
        DummyBody body = new DummyBody("hello", 1);

        assertThatThrownBy(() ->
                idempotencyService.execute("key-4", "POST", "/test", body, DummyResponse.class,
                        () -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("upstream blew up");
                        }))
                .isInstanceOf(IllegalStateException.class);
        // The claim row must be gone — a stuck key would block the retry forever.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = 'key-4'",
                Integer.class)).isZero();

        var retry = idempotencyService.execute("key-4", "POST", "/test", body, DummyResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return new DummyResponse("recovered", 42);
                });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.body().message()).isEqualTo("recovered");
    }

    @Test
    void concurrentClaimYields409InProgress() {
        DummyBody body = new DummyBody("hello", 1);
        // Simulate the race loser: another request just claimed the key
        // (response_status = 0 sentinel, fresh created_at) and is still running.
        String fingerprint = fingerprintOf(body);
        jdbcTemplate.update("""
                INSERT INTO idempotency_record
                    (idempotency_key, request_fingerprint, http_method, request_path,
                     response_status, response_body, created_at, expires_at)
                VALUES ('key-5', ?, 'POST', '/test', 0, NULL, NOW(), NOW() + INTERVAL '1 hour')
                """, fingerprint);

        assertThatThrownBy(() ->
                idempotencyService.execute("key-5", "POST", "/test", body, DummyResponse.class,
                        () -> new DummyResponse("should-not-run", 99)))
                .isInstanceOf(IdempotencyInProgressException.class);
    }

    @Test
    void staleInProgressClaimIsTakenOver() {
        AtomicInteger calls = new AtomicInteger();
        DummyBody body = new DummyBody("hello", 1);
        // A claim whose owner crashed: older than the in-progress grace.
        jdbcTemplate.update("""
                INSERT INTO idempotency_record
                    (idempotency_key, request_fingerprint, http_method, request_path,
                     response_status, response_body, created_at, expires_at)
                VALUES ('key-6', ?, 'POST', '/test', 0, NULL,
                        NOW() - INTERVAL '10 minutes', NOW() + INTERVAL '1 hour')
                """, fingerprintOf(body));

        var result = idempotencyService.execute("key-6", "POST", "/test", body, DummyResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return new DummyResponse("taken-over", 42);
                });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body().message()).isEqualTo("taken-over");
    }

    @Test
    void replayPreservesTheStoredSuccessStatus() {
        DummyBody body = new DummyBody("hello", 1);

        var first = idempotencyService.execute("key-7", "POST", "/test", body, DummyResponse.class,
                201, () -> new DummyResponse("created", 42));
        var replay = idempotencyService.execute("key-7", "POST", "/test", body, DummyResponse.class,
                201, () -> new DummyResponse("should-not-run", 99));

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        // The replayed status must be the ORIGINAL response's status, never a
        // hardcoded 200 — a replayed "created" is still a 201 to the client.
        assertThat(replay.status()).isEqualTo(201);
    }

    /** Mirrors IdempotencyService.fingerprint (SHA-256 of Jackson-canonical body). */
    private String fingerprintOf(DummyBody body) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(mapper.writeValueAsBytes(body)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
