package zw.co.innbucks.middleware.kyc.nationalid;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class NationalIdValidatorRegistryTest {

    private final List<NationalIdValidator> shipped =
            List.of(new ZimbabweNationalIdValidator(), new KenyaNationalIdValidator());

    /** A new market must ship its validator, or KYC cannot be switched on there. */
    @Test
    void everyCountryHasAValidator() {
        for (Country country : Country.values()) {
            NationalIdValidatorRegistry registry =
                    new NationalIdValidatorRegistry(shipped, new CountryProperties(country));
            assertThat(registry.forDeployment().country()).isEqualTo(country);
        }
        assertThat(shipped.stream().map(NationalIdValidator::country))
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(Country.values()));
    }

    @Test
    void aCountryWithoutOneRefusesToStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new NationalIdValidatorRegistry(List.of(new KenyaNationalIdValidator()),
                        new CountryProperties(Country.ZW)))
                .withMessageContaining("ZW");
    }
}
