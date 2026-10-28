package zw.co.innbucks.middleware.customer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tier seam never throws out of the money path: an unrecognised stored
 * value is BASIC, counted and logged — where {@link KycTier#fromDbValue} turned
 * it into a 500 on every payment the customer attempted.
 */
class CustomerRecordKycTierResolverTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CustomerRecordKycTierResolver resolver = new CustomerRecordKycTierResolver(meters);

    private static Customer customer(String tier) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setKycTier(tier);
        return c;
    }

    @Test
    void aKnownTierIsReadFromTheRecord() {
        assertThat(resolver.resolve(customer("enhanced")))
                .isEqualTo(new KycTierResolution(KycTier.ENHANCED, KycTierResolution.Source.CUSTOMER_RECORD));
        assertThat(resolver.resolve(customer(" Standard ")).tier()).isEqualTo(KycTier.STANDARD);
        assertThat(meters.counter("innbucks.kyc_tier.unrecognised").count()).isZero();
    }

    @Test
    void garbageIsBasicCountedAndStillAuthoritative() {
        for (String bad : new String[] {"premium", "", "  ", null, "TIER_2"}) {
            KycTierResolution resolution = resolver.resolve(customer(bad));
            assertThat(resolution.tier()).isEqualTo(KycTier.BASIC);
            assertThat(resolution.source()).isEqualTo(KycTierResolution.Source.UNRECOGNISED_VALUE);
            // A definite read of a bad value: refusals may rest on BASIC.
            assertThat(resolution.authoritative()).isTrue();
        }
        assertThat(meters.counter("innbucks.kyc_tier.unrecognised").count()).isEqualTo(5.0);
        // The strict parser still throws — which is why the money path no longer uses it.
        assertThatThrownBy(() -> KycTier.fromDbValue("premium")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreadableTierIsBasicButCannotBackARefusal() {
        KycTierResolution unavailable = KycTierResolution.unavailable();
        assertThat(unavailable.tier()).isEqualTo(KycTier.BASIC);
        assertThat(unavailable.authoritative()).isFalse();
    }

    @Test
    void tiersKnowTheirOrder() {
        assertThat(KycTier.BASIC.next()).isEqualTo(KycTier.STANDARD);
        assertThat(KycTier.STANDARD.next()).isEqualTo(KycTier.ENHANCED);
        assertThat(KycTier.ENHANCED.next()).isNull();
    }
}
