package zw.co.innbucks.middleware.limits;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seals the limits policy this process enforces into the tamper-evident audit
 * chain once per boot: {@code LIMITS_POLICY_LOADED} (file, digest, country,
 * currency, and any tier inversions the overrides left), plus one
 * {@code LIMITS_OVERRIDE_ACTIVE} per effective value an override changed —
 * the operator's own and the ones derived from them.
 *
 * <p>Runs on {@link ApplicationReadyEvent}, i.e. after Flyway has created
 * {@code audit_event} and the context is fully up.
 *
 * <p><b>Best-effort, by decision.</b> {@link AuditService#record} swallows its
 * own failures (counted on {@code innbucks.audit.write_failures}), so a boot
 * whose audit row could not be written still serves. Refusing to start over
 * a lost audit row would turn an audit-store blip into a payments outage, and
 * the same facts are in the boot log (digest, effective values, overrides)
 * and pinned by the deployed image sha. Revisit when the chain grows a
 * must-write variant.
 */
@Component
@RequiredArgsConstructor
public class PaymentLimitsPolicyAuditor {

    private final PaymentLimitsPolicy policy;
    private final AuditService auditService;

    @EventListener(ApplicationReadyEvent.class)
    public void recordPolicyInForce() {
        Map<String, Object> loaded = new LinkedHashMap<>();
        loaded.put("source", policy.source());
        loaded.put("sha256", policy.sha256());
        loaded.put("country", policy.country().name());
        loaded.put("currency", policy.currency());
        loaded.put("dormant", policy.isDormant());
        loaded.put("overrides", policy.overrides().size());
        if (!policy.warnings().isEmpty()) {
            loaded.put("warnings", policy.warnings());
        }
        auditService.record(AuditAction.LIMITS_POLICY_LOADED, AuditOutcome.SUCCESS, null, null, loaded);

        for (PaymentLimitsPolicy.AppliedOverride override : policy.overrides()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("tier", override.tier().dbValue());
            metadata.put("kind", override.kind().wireName());
            metadata.put("fileValue", override.fileValue());
            metadata.put("overrideValue", override.overrideValue());
            if (override.derived()) {
                metadata.put("derivedFrom", override.derivedFrom().wireName());
            }
            metadata.put("sha256", policy.sha256());
            auditService.record(AuditAction.LIMITS_OVERRIDE_ACTIVE, AuditOutcome.SUCCESS, null, null, metadata);
        }
    }
}
