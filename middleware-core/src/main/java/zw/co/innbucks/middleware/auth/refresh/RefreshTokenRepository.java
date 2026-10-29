package zw.co.innbucks.middleware.auth.refresh;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends CrudRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            UPDATE refresh_token
               SET revoked_at = :revokedAt,
                   revocation_reason = :reason
             WHERE family_id = :familyId
               AND revoked_at IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("revokedAt") Instant revokedAt,
                     @Param("reason") String reason);

    /**
     * Sign out everywhere: every still-live row of EVERY family the customer
     * holds. Rows already revoked keep their original time and reason. The
     * count is ROWS, not sessions — a family's rotated ancestors are revoked
     * with its live leaf, exactly as {@link #revokeFamily} does, so presenting
     * an old token afterwards reads as invalid rather than as a replay.
     * Call it through {@link RefreshTokenService#revokeAllSessions}, which
     * takes the customer-row lock that makes it race-free against rotation.
     */
    @Modifying
    @Query("""
            UPDATE refresh_token
               SET revoked_at = :revokedAt,
                   revocation_reason = :reason
             WHERE customer_id = :customerId
               AND revoked_at IS NULL
            """)
    int revokeAllForCustomer(@Param("customerId") UUID customerId,
                             @Param("revokedAt") Instant revokedAt,
                             @Param("reason") String reason);

    /**
     * Records a rotation on the presented row — ONLY while that row is still
     * unrevoked AND not yet rotated. Replaces a whole-entity {@code save()},
     * which wrote the stale {@code revoked_at = NULL} it had read back over a
     * revocation committed in between (undoing a logout or a
     * sign-out-everywhere).
     *
     * <p>The {@code replaced_by_jti IS NULL} half is what makes two
     * OVERLAPPING rotations of one token a replay rather than a fork. Both
     * read the row unrotated before either commits, and the second's UPDATE
     * waits on the first's row lock; Postgres then re-evaluates this WHERE on
     * the committed row. Guarded on {@code revoked_at} alone it still matched,
     * so both rotations succeeded: two live chains in one family and no
     * {@code REFRESH_REPLAY_DETECTED} — the theft signal silent for exactly an
     * attacker racing the real holder. 0 rows = the row was revoked or rotated
     * after rotation read it; the caller re-reads it to tell which.
     */
    @Modifying
    @Query("""
            UPDATE refresh_token
               SET replaced_by_jti = :successorJti
             WHERE jti = :jti
               AND revoked_at IS NULL
               AND replaced_by_jti IS NULL
            """)
    int markReplaced(@Param("jti") UUID jti,
                     @Param("successorJti") UUID successorJti);
}
