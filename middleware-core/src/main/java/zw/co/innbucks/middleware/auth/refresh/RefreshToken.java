package zw.co.innbucks.middleware.auth.refresh;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("refresh_token")
public class RefreshToken {

    @Id
    private UUID jti;

    /**
     * SHA-256 hex (lowercase, 64 chars) of the opaque client secret. We persist
     * only this hash — never the secret itself — so a DB read (SQLi / backup /
     * replica) can't yield a usable refresh token. Refresh / logout hash the
     * presented value and look the row up by this column.
     */
    private String tokenHash;

    /**
     * The plaintext client secret (a fresh random UUID), returned to the client
     * on issue / rotate and NEVER persisted. {@code @Transient} keeps Spring Data
     * JDBC from mapping it to a column.
     */
    @Transient
    private String secret;

    private UUID customerId;

    private UUID familyId;

    private String deviceHash;

    private Instant issuedAt;

    private Instant expiresAt;

    @Column("replaced_by_jti")
    private UUID replacedByJti;

    private Instant revokedAt;

    private String revocationReason;
}
