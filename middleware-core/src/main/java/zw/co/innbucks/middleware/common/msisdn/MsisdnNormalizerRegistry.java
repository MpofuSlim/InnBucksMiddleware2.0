package zw.co.innbucks.middleware.common.msisdn;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MsisdnNormalizerRegistry {

    private final Map<Country, MsisdnNormalizer> byCountry;
    private final MsisdnNormalizer activeNormalizer;

    public MsisdnNormalizerRegistry(List<MsisdnNormalizer> normalizers, CountryProperties countryProperties) {
        this.byCountry = normalizers.stream()
                .collect(Collectors.toUnmodifiableMap(MsisdnNormalizer::country, Function.identity()));
        Country active = countryProperties.country();
        MsisdnNormalizer resolved = byCountry.get(active);
        if (resolved == null) {
            throw new IllegalStateException(
                    "No MsisdnNormalizer registered for country " + active
                            + "; cannot start without one");
        }
        this.activeNormalizer = resolved;
    }

    public MsisdnNormalizer forDeployment() {
        return activeNormalizer;
    }

    public MsisdnNormalizer forCountry(Country country) {
        MsisdnNormalizer normalizer = byCountry.get(country);
        if (normalizer == null) {
            throw new IllegalStateException("No MsisdnNormalizer registered for country " + country);
        }
        return normalizer;
    }
}
