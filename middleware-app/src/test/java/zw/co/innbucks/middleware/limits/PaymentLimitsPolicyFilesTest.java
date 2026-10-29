package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.common.country.Country;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PACKAGED policy files, checked at PR time rather than at a cell's boot.
 * Plain JUnit (no Spring, no Docker): every supported {@link Country} must
 * ship a {@code limits/limits-<iso>.yaml} that the real loader accepts —
 * adding a market without one fails this build, not that market's first
 * deploy.
 */
class PaymentLimitsPolicyFilesTest {

    private static final Pattern CURRENCY = Pattern.compile("(?m)^currency:\\s*([A-Z]{3})\\s*$");

    @Test
    void everySupportedCountryShipsAValidPolicyFile() throws IOException {
        for (Country country : Country.values()) {
            String path = PaymentLimitsPolicyLoader.resourcePathFor(country);
            byte[] bytes = read(path);
            assertThat(bytes).as("%s must be packaged for %s", path, country).isNotNull();

            // Load it as THAT cell would: its own country, and the currency it
            // declares as the cell currency (the cell-currency match itself is
            // the loader's unit test; here the point is that the file parses
            // and passes every consistency rule).
            Matcher currency = CURRENCY.matcher(new String(bytes, StandardCharsets.UTF_8));
            assertThat(currency.find()).as("%s states a currency", path).isTrue();
            PaymentLimitsPolicy policy = PaymentLimitsPolicyLoader.load(path, bytes, country,
                    currency.group(1), Map.of());

            assertThat(policy.country()).isEqualTo(country);
            assertThat(policy.sha256()).hasSize(64);
        }
    }

    @Test
    void theZimbabweCellIsUsd() throws IOException {
        // The one cell in production. A wrong currency here would refuse its
        // boot against FINERACT_CURRENCY=USD — catch it before a deploy does.
        byte[] bytes = read(PaymentLimitsPolicyLoader.resourcePathFor(Country.ZW));
        assertThat(PaymentLimitsPolicyLoader.load("limits/limits-zw.yaml", bytes, Country.ZW, "USD", Map.of())
                .currency()).isEqualTo("USD");
    }

    @Test
    void theShippedFilesAreDormantUntilComplianceSuppliesValues() throws IOException {
        // Values are pending compliance. When they land, this assertion is the
        // one line that changes in the same PR — deliberately, so nobody can
        // switch limits on without also editing the test that says they are off.
        for (Country country : Country.values()) {
            String path = PaymentLimitsPolicyLoader.resourcePathFor(country);
            byte[] bytes = read(path);
            Matcher currency = CURRENCY.matcher(new String(bytes, StandardCharsets.UTF_8));
            assertThat(currency.find()).isTrue();
            assertThat(PaymentLimitsPolicyLoader.load(path, bytes, country, currency.group(1), Map.of())
                    .isDormant()).as("%s is dormant", path).isTrue();
        }
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = PaymentLimitsPolicyFilesTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
