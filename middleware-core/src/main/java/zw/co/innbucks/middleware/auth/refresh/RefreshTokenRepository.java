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
     * Revokes every refresh family of one customer at once — the "sign me out
     * everywhere" a credential change owes — and answers how many LIVE
     * sessions that ended.
     *
     * <p>Every un-revoked row goes, rotated predecessors included (as
     * {@link #revokeFamily} does), so a stale token presented later reads as
     * plainly invalid rather than as a replay alarm. But a rotation leaves its
     * predecessor un-revoked, so the raw row count would be roughly the number
     * of refreshes, not of sessions; the count is taken over the rows that were
     * live — a family's un-rotated, unexpired head, exactly one per session.
     *
     * <p>Deliberately NOT {@code @Modifying}: the top-level statement is the
     * SELECT that returns the count, with the UPDATE in a data-modifying CTE —
     * one atomic statement.
     */
    @Query("""
            WITH revoked AS (
                UPDATE refresh_token
                   SET revoked_at = :revokedAt,
                       revocation_reason = :reason
                 WHERE customer_id = :customerId
                   AND revoked_at IS NULL
             RETURNING replaced_by_jti, expires_at
            )
            SELECT COUNT(*)
              FROM revoked
             WHERE replaced_by_jti IS NULL
               AND expires_at > :revokedAt
            """)
    int revokeAllForCustomer(@Param("customerId") UUID customerId,
                             @Param("revokedAt") Instant revokedAt,
                             @Param("reason") String reason);
}
