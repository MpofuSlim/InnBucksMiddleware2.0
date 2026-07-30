package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IdempotencyRecordPrunerTest {

    @Test
    void deletesRecordsExpiredBeforeNow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-05-26T03:35:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        IdempotencyService service = new IdempotencyService(
                jdbc, mock(ObjectMapper.class), mock(IdempotencyProperties.class), clock);

        service.pruneExpiredIdempotencyRecords();

        // Deletes via the expires_at predicate, cutoff = now (no forensic grace —
        // expired idempotency records are useless caches).
        verify(jdbc).update(contains("DELETE FROM idempotency_record"), eq(Timestamp.from(now)));
    }
}
