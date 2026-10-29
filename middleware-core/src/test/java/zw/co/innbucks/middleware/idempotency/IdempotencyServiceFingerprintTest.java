package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service's use of the keyed fingerprint, without Postgres: what a NEW
 * claim stores, and how a lost claim compares against a stored row — keyed
 * (v2) and legacy (bare SHA-256, written before the deploy). The SQL itself is
 * pinned against real Postgres by {@code IdempotencyServiceTest} (CI).
 */
class IdempotencyServiceFingerprintTest {

    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    record Body(String msisdn, String nationalId) {
    }

    record Response(String message) {
    }

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IdempotencyFingerprinter fingerprinter =
            new IdempotencyFingerprinter("test-national-id-hmac-key-at-least-32-bytes-long", mapper);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdempotencyService service = new IdempotencyService(jdbc, mapper,
            new IdempotencyProperties(Duration.ofHours(24)), fingerprinter, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Body body = new Body("+263771234567", "63-123456A42");
    private final AtomicInteger runs = new AtomicInteger();

    private Response work() {
        runs.incrementAndGet();
        return new Response("ran");
    }

    @Test
    void aNewClaimStoresTheKeyedFingerprintNeverTheBareHash() throws Exception {
        when(jdbc.update(contains("INSERT INTO idempotency_record"), any(Object[].class))).thenReturn(1);

        service.execute("k-1", "POST", "/register", body, Response.class, this::work);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO idempotency_record"), args.capture());
        String stored = (String) args.getValue()[1];
        assertThat(stored).isEqualTo(fingerprinter.fingerprint(body).value()).startsWith("v2:");
        assertThat(stored).doesNotContain(legacyHex(body));
    }

    @Test
    void aRetryOfTheSameBodyReplaysAgainstAKeyedRow() throws Exception {
        claimLostTo(fingerprinter.fingerprint(body).value());

        var result = service.execute("k-2", "POST", "/register", body, Response.class, this::work);

        assertThat(result.replayed()).isTrue();
        assertThat(result.body().message()).isEqualTo("first");
        assertThat(runs).hasValue(0);
    }

    @Test
    void aDifferentBodyUnderTheSameKeyIsStillAConflict() throws Exception {
        claimLostTo(fingerprinter.fingerprint(body).value());

        assertThatThrownBy(() -> service.execute("k-3", "POST", "/register",
                new Body("+263771234567", "63-999999Z99"), Response.class, this::work))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(runs).hasValue(0);
    }

    @Test
    void aRowWrittenBeforeTheDeployStillReplays() throws Exception {
        claimLostTo(legacyHex(body));

        var result = service.execute("k-4", "POST", "/register", body, Response.class, this::work);

        assertThat(result.replayed()).isTrue();
        assertThat(runs).hasValue(0);
    }

    @Test
    void aLegacyRowWithADifferentBodyIsStillAConflict() throws Exception {
        claimLostTo(legacyHex(body));

        assertThatThrownBy(() -> service.execute("k-5", "POST", "/register",
                new Body("+263771234567", "63-999999Z99"), Response.class, this::work))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    /** The INSERT … ON CONFLICT DO NOTHING lost; the winner's COMPLETED row is read back. */
    @SuppressWarnings("unchecked")
    private void claimLostTo(String storedFingerprint) throws Exception {
        when(jdbc.update(contains("INSERT INTO idempotency_record"), any(Object[].class))).thenReturn(0);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("request_fingerprint")).thenReturn(storedFingerprint);
        when(row.getInt("response_status")).thenReturn(200);
        when(row.getString("response_body")).thenReturn("{\"message\":\"first\"}");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW.minusSeconds(5)));
        when(jdbc.query(contains("SELECT request_fingerprint"), any(RowMapper.class), anyString()))
                .thenAnswer(inv -> List.of(((RowMapper<Object>) inv.getArgument(1)).mapRow(row, 0)));
    }

    private String legacyHex(Object value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
    }
}
