package zw.co.innbucks.middleware.auth.jwt;

import org.springframework.security.oauth2.jwt.Jwt;
import zw.co.innbucks.middleware.common.country.Country;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record InnbucksPrincipal(

        String subject,
        Country country,
        String kycTier,
        Set<String> scopes,
        String deviceHash,
        String nationalIdHash,
        Instant authTime,
        Instant issuedAt,
        Instant expiresAt
) {

    public static InnbucksPrincipal fromJwt(Jwt jwt) {
        String countryClaim = jwt.getClaimAsString(InnbucksClaims.COUNTRY);
        Country country = (countryClaim == null) ? null : Country.valueOf(countryClaim);

        List<String> scopeList = jwt.getClaimAsStringList(InnbucksClaims.SCOPES);
        Set<String> scopes = (scopeList == null) ? Set.of() : Set.copyOf(scopeList);

        Long authTimeEpoch = jwt.getClaim(InnbucksClaims.AUTH_TIME);
        Instant authTime = (authTimeEpoch == null) ? null : Instant.ofEpochSecond(authTimeEpoch);

        return new InnbucksPrincipal(
                jwt.getSubject(),
                country,
                jwt.getClaimAsString(InnbucksClaims.KYC_TIER),
                scopes,
                jwt.getClaimAsString(InnbucksClaims.DEVICE_ID),
                jwt.getClaimAsString(InnbucksClaims.NATIONAL_ID_HASH),
                authTime,
                jwt.getIssuedAt(),
                jwt.getExpiresAt()
        );
    }
}
