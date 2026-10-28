package zw.co.innbucks.middleware.kyc.nationalid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.SignupMatch;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The national-ID facts KYC keeps about a typed number, all computed from its
 * CANONICAL form under the existing {@code NATIONAL_ID_HMAC_KEY}:
 *
 * <ul>
 *   <li>its HMAC — the duplicate-search key, and what the idempotency
 *       fingerprint carries instead of the number;</li>
 *   <li>its display mask;</li>
 *   <li>whether it matches the number given at sign-up, whose hash was
 *       taken over the number AS TYPED (trimmed only), so every common
 *       spelling is tried before answering {@code no_match}.</li>
 * </ul>
 */
@Component
@ConditionalOnKycEnabled
public class KycNationalIds {

    private final NationalIdValidator validator;
    private final NationalIdHasher hasher;

    @Autowired
    public KycNationalIds(NationalIdValidatorRegistry registry, NationalIdHasher hasher) {
        this(registry.forDeployment(), hasher);
    }

    KycNationalIds(NationalIdValidator validator, NationalIdHasher hasher) {
        this.validator = validator;
        this.hasher = hasher;
    }

    /** @throws InvalidNationalIdException when the input is not a well-formed number */
    public Canonical canonicalise(String input) {
        String canonical = validator.canonicalise(input);
        return new Canonical(canonical, hasher.hash(canonical), NationalIds.mask(canonical));
    }

    /** The HMACs of every common spelling — what a legacy sign-up hash could be. */
    public Set<String> spellingHashes(String canonical) {
        Set<String> out = new LinkedHashSet<>();
        for (String spelling : validator.commonSpellings(canonical)) {
            out.add(hasher.hash(spelling));
        }
        out.add(hasher.hash(canonical));
        return out;
    }

    /** {@code signupHash} is the customer's {@code national_id_hash}, possibly null. */
    public SignupMatch signupMatch(String signupHash, String canonical) {
        if (signupHash == null || signupHash.isBlank()) {
            return SignupMatch.NONE_GIVEN;
        }
        return spellingHashes(canonical).contains(signupHash) ? SignupMatch.MATCH : SignupMatch.NO_MATCH;
    }

    /**
     * A canonical ID with its HMAC and mask. {@link #toString()} prints only
     * the mask: this value travels through code that logs.
     */
    public record Canonical(String value, String hmac, String masked) {
        @Override
        public String toString() {
            return "NationalId[" + masked + "]";
        }
    }
}
