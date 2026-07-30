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
}
