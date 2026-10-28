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
     * unrevoked. Replaces a whole-entity {@code save()}, which wrote the stale
     * {@code revoked_at = NULL} it had read back over a revocation committed
     * in between (undoing a logout or a sign-out-everywhere). 0 rows = the
     * row was revoked after rotation read it; the caller refuses.
     */
    @Modifying
    @Query("""
            UPDATE refresh_token
               SET replaced_by_jti = :successorJti
             WHERE jti = :jti
               AND revoked_at IS NULL
            """)
    int markReplaced(@Param("jti") UUID jti,
                     @Param("successorJti") UUID successorJti);
}
