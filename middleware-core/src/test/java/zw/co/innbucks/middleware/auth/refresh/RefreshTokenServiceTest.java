package zw.co.innbucks.middleware.auth.refresh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenInvalidException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the statement ORDER that makes "sign out everywhere" hold against a
 * rotation in flight. What the locks actually do needs Postgres — see
 * {@code PinResetSignOutIntegrationTest} — but the guarantee also depends on
 * rotation taking its customer lock, and marking the presented row, BEFORE
 * the successor exists. A reorder here reopens the race silently.
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");
    private static final String SECRET = "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
    private static final String DEVICE = "device-1";

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final AuthProperties authProperties = mock(AuthProperties.class);
    private final AuditService audit = mock(AuditService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RefreshTokenService service = new RefreshTokenService(
            repository, authProperties, Clock.fixed(NOW, ZoneOffset.UTC), audit, jdbc);

    private final UUID customerId = UUID.randomUUID();
    private final RefreshToken current = new RefreshToken();

    @BeforeEach
    void setUp() {
        when(authProperties.refreshTokenTtl()).thenReturn(Duration.ofDays(30));
        current.setJti(UUID.randomUUID());
        current.setCustomerId(customerId);
        current.setFamilyId(UUID.randomUUID());
        current.setDeviceHash(DEVICE);
        current.setIssuedAt(NOW.minusSeconds(600));
        current.setExpiresAt(NOW.plus(Duration.ofDays(29)));
        when(repository.findByTokenHash(sha256(SECRET))).thenReturn(Optional.of(current));
    }

    @Test
    void rotationLocksTheCustomerAndMarksTheRowBeforeTheSuccessorExists() {
        when(repository.markReplaced(eq(current.getJti()), any())).thenReturn(1);

        RefreshToken next = service.rotate(SECRET, DEVICE);

        InOrder order = inOrder(jdbc, repository);
        order.verify(jdbc).queryForList(contains("FOR SHARE"), eq(customerId));
        order.verify(repository).markReplaced(current.getJti(), next.getJti());
        order.verify(jdbc).update(contains("INSERT INTO refresh_token"), any(Object[].class));
        assertThat(next.getFamilyId()).isEqualTo(current.getFamilyId());
        verify(audit).record(AuditAction.REFRESH_SUCCESS, AuditOutcome.SUCCESS, customerId, DEVICE);
    }

    @Test
    void aRowRevokedAfterRotationReadItIsRefusedAndNoSuccessorIsMinted() {
        // A PIN reset / logout committed between the read and the mark.
        when(repository.markReplaced(eq(current.getJti()), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate(SECRET, DEVICE))
                .isInstanceOf(RefreshTokenInvalidException.class);

        verify(jdbc, never()).update(contains("INSERT INTO refresh_token"), any(Object[].class));
        verify(audit, never()).record(eq(AuditAction.REFRESH_SUCCESS), any(), any(), any());
    }

    @Test
    void rotationNeverSavesTheWholeStaleRow() {
        // save(current) wrote back the revoked_at = NULL it had read, undoing
        // a revocation committed in between. Only the guarded UPDATE may write.
        when(repository.markReplaced(eq(current.getJti()), any())).thenReturn(1);

        service.rotate(SECRET, DEVICE);

        verify(repository, never()).save(any());
    }

    @Test
    void revokeAllSessionsTakesTheCustomerLockFirstAndRevokesByCustomer() {
        when(repository.revokeAllForCustomer(customerId, NOW, "pin_reset")).thenReturn(4);

        int revoked = service.revokeAllSessions(customerId, "pin_reset");

        assertThat(revoked).isEqualTo(4);
        InOrder order = inOrder(jdbc, repository);
        order.verify(jdbc).queryForList(contains("FOR NO KEY UPDATE"), eq(customerId));
        order.verify(repository).revokeAllForCustomer(customerId, NOW, "pin_reset");
    }

    @Test
    void theTwoLocksConflictAndNeitherBlocksForeignKeyInserts() {
        when(repository.markReplaced(eq(current.getJti()), any())).thenReturn(1);
        service.rotate(SECRET, DEVICE);
        service.revokeAllSessions(customerId, "pin_reset");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).queryForList(sql.capture(), eq(customerId));
        // SHARE vs NO KEY UPDATE conflict — that is the serialisation. A plain
        // FOR UPDATE on either side would also queue every refresh_token and
        // ledger_transaction INSERT (their FK checks take KEY SHARE).
        assertThat(sql.getAllValues().get(0)).contains("FOR SHARE").doesNotContain("FOR UPDATE");
        assertThat(sql.getAllValues().get(1)).contains("FOR NO KEY UPDATE");
        assertThat(sql.getAllValues()).noneMatch(s -> s.matches("(?s).*FOR\\s+UPDATE.*"));
    }

    @Test
    void aReplayStillRevokesTheFamilyWithoutTakingTheCustomerLock() {
        current.setReplacedByJti(UUID.randomUUID());

        assertThatThrownBy(() -> service.rotate(SECRET, DEVICE))
                .isInstanceOf(zw.co.innbucks.middleware.auth.exception.RefreshTokenReplayException.class);

        verify(repository).revokeFamily(current.getFamilyId(), NOW, "replay_detected");
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    // ------------------------------------------------ liveSessionCustomer

    @Test
    void aLiveTokenNamesItsCustomerAndSpendsNothing() {
        // /auth/refresh asks the core about a block between this read and
        // rotate(): the token must still be unspent while it does.
        assertThat(service.liveSessionCustomer(SECRET, DEVICE)).contains(customerId);

        verify(repository, never()).markReplaced(any(), any());
        verify(repository, never()).revokeFamily(any(), any(), anyString());
        verify(repository, never()).revokeAllForCustomer(any(), any(), anyString());
        verify(repository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(jdbc, audit);
    }

    @Test
    void everyTokenRotationWouldRefuseIsNotLiveAndIsNotPunishedHere() {
        // Revoked, expired, already rotated (a replay), wrong device, unknown:
        // all empty. None is revoked or audited HERE — rotate() is the one
        // place that does, and the caller always goes on to call it.
        RefreshToken revoked = copyOfCurrent();
        revoked.setRevokedAt(NOW.minusSeconds(1));
        RefreshToken expired = copyOfCurrent();
        expired.setExpiresAt(NOW.minusSeconds(1));
        RefreshToken replayed = copyOfCurrent();
        replayed.setReplacedByJti(UUID.randomUUID());
        RefreshToken noDevice = copyOfCurrent();
        noDevice.setDeviceHash(null);

        for (RefreshToken notLive : java.util.List.of(revoked, expired, replayed, noDevice)) {
            when(repository.findByTokenHash(sha256(SECRET))).thenReturn(Optional.of(notLive));
            assertThat(service.liveSessionCustomer(SECRET, DEVICE)).isEmpty();
        }
        when(repository.findByTokenHash(sha256(SECRET))).thenReturn(Optional.of(current));
        assertThat(service.liveSessionCustomer(SECRET, "another-device")).isEmpty();
        assertThat(service.liveSessionCustomer(SECRET, null)).isEmpty();
        when(repository.findByTokenHash(sha256(SECRET))).thenReturn(Optional.empty());
        assertThat(service.liveSessionCustomer(SECRET, DEVICE)).isEmpty();

        verify(repository, never()).revokeFamily(any(), any(), anyString());
        org.mockito.Mockito.verifyNoInteractions(jdbc, audit);
    }

    @Test
    void aTokenExpiringThisInstantIsStillLiveExactlyAsRotationJudgesIt() {
        // rotate() refuses only expires_at BEFORE now; the two must agree, or
        // a token could skip the block check and still rotate.
        current.setExpiresAt(NOW);
        when(repository.markReplaced(eq(current.getJti()), any())).thenReturn(1);

        assertThat(service.liveSessionCustomer(SECRET, DEVICE)).contains(customerId);
        assertThat(service.rotate(SECRET, DEVICE)).isNotNull();
    }

    private RefreshToken copyOfCurrent() {
        RefreshToken copy = new RefreshToken();
        copy.setJti(current.getJti());
        copy.setCustomerId(current.getCustomerId());
        copy.setFamilyId(current.getFamilyId());
        copy.setDeviceHash(current.getDeviceHash());
        copy.setIssuedAt(current.getIssuedAt());
        copy.setExpiresAt(current.getExpiresAt());
        return copy;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
