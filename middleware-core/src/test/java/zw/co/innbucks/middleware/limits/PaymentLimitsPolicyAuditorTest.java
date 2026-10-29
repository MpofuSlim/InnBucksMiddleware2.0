package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.customer.KycTier;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the boot audit seals when overrides are active: one row per effective
 * value an override changed (derived ones say what they follow), and the tier
 * inversions the overrides left, which no longer refuse boot. The rows
 * themselves are written against real Postgres by the app module's
 * {@code PaymentLimitsIntegrationTest}.
 */
class PaymentLimitsPolicyAuditorTest {

    private static final String DORMANT = """
            country: ZW
            currency: USD
            tiers:
              basic: {}
              standard: {}
              enhanced: {}
            """;

    private final AuditService auditService = mock(AuditService.class);

    @Test
    @SuppressWarnings("unchecked")
    void derivedValuesAndTierInversionsAreSealed() {
        // Standard clamped on a dormant file: standard daily lowers its own
        // per_payment, and standard is left stricter than (unlimited) basic.
        Map<KycTier, Map<LimitKind, Long>> overrides = new EnumMap<>(KycTier.class);
        overrides.put(KycTier.STANDARD, new EnumMap<>(Map.of(LimitKind.PER_PAYMENT, 200000L,
                LimitKind.DAILY, 100000L)));
        PaymentLimitsPolicy policy = PaymentLimitsPolicyLoader.load("limits/limits-zw.yaml",
                DORMANT.getBytes(StandardCharsets.UTF_8), Country.ZW, "USD", overrides);

        new PaymentLimitsPolicyAuditor(policy, auditService).recordPolicyInForce();

        ArgumentCaptor<Map<String, ?>> loaded = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditAction.LIMITS_POLICY_LOADED), eq(AuditOutcome.SUCCESS),
                isNull(), isNull(), loaded.capture());
        assertThat(asObjects(loaded.getValue())).containsEntry("overrides", 2);
        assertThat((List<String>) loaded.getValue().get("warnings"))
                .hasSize(2)
                .allSatisfy(w -> assertThat(w).contains("tier 'standard'").contains("tier 'basic'"));

        ArgumentCaptor<Map<String, ?>> rows = ArgumentCaptor.forClass(Map.class);
        verify(auditService, times(2)).record(eq(AuditAction.LIMITS_OVERRIDE_ACTIVE), eq(AuditOutcome.SUCCESS),
                isNull(), isNull(), rows.capture());
        assertThat(asObjects(rows.getAllValues().get(0)))
                .containsEntry("tier", "standard")
                .containsEntry("kind", "per_payment")
                .containsEntry("overrideValue", 100000L)
                .containsEntry("derivedFrom", "daily");
        assertThat(asObjects(rows.getAllValues().get(1)))
                .containsEntry("kind", "daily")
                .doesNotContainKey("derivedFrom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPolicyWithNothingToWarnAboutWritesNoWarningsKey() {
        PaymentLimitsPolicy policy = PaymentLimitsPolicyLoader.load("limits/limits-zw.yaml",
                DORMANT.getBytes(StandardCharsets.UTF_8), Country.ZW, "USD", Map.of());

        new PaymentLimitsPolicyAuditor(policy, auditService).recordPolicyInForce();

        ArgumentCaptor<Map<String, ?>> loaded = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditAction.LIMITS_POLICY_LOADED), eq(AuditOutcome.SUCCESS),
                isNull(), isNull(), loaded.capture());
        assertThat(asObjects(loaded.getValue())).containsEntry("dormant", true).doesNotContainKey("warnings");
    }

    private static Map<String, Object> asObjects(Map<String, ?> metadata) {
        return new LinkedHashMap<>(metadata);
    }
}
