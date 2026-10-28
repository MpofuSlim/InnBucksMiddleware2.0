package zw.co.innbucks.middleware.kyc.nationalid;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the cell's {@link NationalIdValidator}, like
 * {@code MsisdnNormalizerRegistry}: with KYC on, a country without one
 * refuses to boot rather than fail at a customer's first submission.
 * ({@code NationalIdValidatorRegistryTest} also requires one per
 * {@link Country}, so a new market cannot forget it.)
 */
@Component
@ConditionalOnKycEnabled
public class NationalIdValidatorRegistry {

    private final Map<Country, NationalIdValidator> byCountry;
    private final NationalIdValidator active;

    public NationalIdValidatorRegistry(List<NationalIdValidator> validators, CountryProperties countryProperties) {
        this.byCountry = validators.stream()
                .collect(Collectors.toUnmodifiableMap(NationalIdValidator::country, Function.identity()));
        NationalIdValidator resolved = byCountry.get(countryProperties.country());
        if (resolved == null) {
            throw new IllegalStateException("No NationalIdValidator registered for country "
                    + countryProperties.country() + "; KYC cannot start without one");
        }
        this.active = resolved;
    }

    public NationalIdValidator forDeployment() {
        return active;
    }
}
