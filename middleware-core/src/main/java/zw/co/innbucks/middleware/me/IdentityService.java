package zw.co.innbucks.middleware.me;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Identity tokens: a way for a third party the customer uses (the Magaya
 * app's profile-picture function) to learn WHO the customer is without ever
 * holding a token that can act for them.
 *
 * <p>The app trades its access token for an identity token and hands that
 * on. The identity token's only scope is {@code identity:read}: it reads
 * {@code GET /me/identity} and nothing else. Every other endpoint needs a
 * {@code customer:*} scope at the security filter as well as in its own
 * {@code @PreAuthorize}, so a leaked identity token can never move money or
 * read a balance. It carries no KYC tier and no device, and cannot mint
 * another identity token. It expires two minutes after issue; the shared
 * decoder allows its usual 60-second clock skew on top, and it can be used
 * more than once until then. Read-only of the customer's own id and number,
 * so neither is worth single-use bookkeeping.
 *
 * <p>Every identity token shares the access token's issuer and audience and
 * names no relying party: fine while one third party receives them. Before a
 * second one does, give identity tokens their own audience per relying
 * party, so a token made for one cannot be presented to the other.
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

    /**
     * Long enough for the app to hand the token over and the third party to
     * make its call; short enough that a token caught in a log expires within
     * minutes (this plus the decoder's 60 s skew).
     */
    public static final Duration IDENTITY_TOKEN_TTL = Duration.ofMinutes(2);

    private final JwtIssuer jwtIssuer;
    private final CountryProperties countryProperties;
    private final CustomerRepository customerRepository;

    public IdentityTokenResponse issueToken(UUID customerId) {
        String token = jwtIssuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), countryProperties.country(), null,
                Set.of(CustomerScopes.IDENTITY_READ), null), IDENTITY_TOKEN_TTL);
        return new IdentityTokenResponse(token, IDENTITY_TOKEN_TTL.toSeconds());
    }

    public IdentityResponse identityOf(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        return new IdentityResponse(customer.getId(), customer.getMsisdn());
    }
}
