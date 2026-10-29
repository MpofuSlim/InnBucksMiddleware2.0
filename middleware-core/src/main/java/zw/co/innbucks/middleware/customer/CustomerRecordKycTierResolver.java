package zw.co.innbucks.middleware.customer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Today's tier source: the local {@code customer.kyc_tier} column, read from
 * the row the request already loaded — no extra query, so it cannot fail.
 *
 * <p>An unrecognised stored value maps to BASIC (the tightest tier), is
 * counted on {@code innbucks.kyc_tier.unrecognised} and logged at WARN. It
 * never throws: the old strict {@code KycTier.fromDbValue} (now deleted) did,
 * which turned one bad value in a row into a 500 on every payment that
 * customer attempted. The column has no CHECK constraint, and once tiers are
 * written from outside the middleware a typo or a new tier name is a matter
 * of time.
 */
@Slf4j
@Component
public class CustomerRecordKycTierResolver implements KycTierResolver {

    private final Counter unrecognised;

    public CustomerRecordKycTierResolver(MeterRegistry meterRegistry) {
        this.unrecognised = Counter.builder("innbucks.kyc_tier.unrecognised")
                .description("Customer rows whose kyc_tier is not a known tier — treated as BASIC; fix the row")
                .register(meterRegistry);
    }

    @Override
    public KycTierResolution resolve(Customer customer) {
        Optional<KycTier> tier = KycTier.parse(customer.getKycTier());
        if (tier.isPresent()) {
            return KycTierResolution.of(tier.get());
        }
        unrecognised.increment();
        log.warn("Customer {} has an unrecognised kyc_tier '{}' — treating as basic. Fix the row.",
                customer.getId(), customer.getKycTier());
        return KycTierResolution.unrecognised();
    }
}
