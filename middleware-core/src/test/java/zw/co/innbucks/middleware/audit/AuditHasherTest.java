package zw.co.innbucks.middleware.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AuditHasherTest {

    private static final String KEY = "test-audit-hmac-secret-at-least-32-bytes-long";
    private static final Instant WHEN = Instant.parse("2026-07-30T10:15:30.123456Z");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final AuditHasher hasher = new AuditHasher(KEY);

    private String rowHmac(String action) {
        return hasher.rowHmac(WHEN, "KE", CUSTOMER, action, "success",
                "corr-1", "10.0.0.1", "agent", "device-1", null);
    }

    @Test
    void rowHmacIsDeterministicAndContentSensitive() {
        assertThat(rowHmac("login_success")).isEqualTo(rowHmac("login_success"));
        // Any single field change must change the seal — that's the tamper evidence.
        assertThat(rowHmac("login_success")).isNotEqualTo(rowHmac("login_failure"));
        assertThat(hasher.rowHmac(WHEN, "KE", null, "login_success", "success",
                "corr-1", "10.0.0.1", "agent", "device-1", null))
                .isNotEqualTo(rowHmac("login_success"));
    }

    @Test
    void chainBindsEachRowToItsPredecessor() {
        String row1 = rowHmac("login_success");
        String row2 = rowHmac("pin_set");

        String genesis = hasher.chainHmac(null, row1);
        String second = hasher.chainHmac(genesis, row2);

        // Recomputable link-by-link...
        assertThat(hasher.chainHmac(genesis, row2)).isEqualTo(second);
        // ...and deleting/reordering the predecessor breaks the recomputation.
        assertThat(hasher.chainHmac(null, row2)).isNotEqualTo(second);
        assertThat(hasher.chainHmac(second, row1)).isNotEqualTo(genesis);
    }

    @Test
    void canonicalizeJsonNormalizesKeyOrder() {
        // Postgres jsonb does not preserve key order; both orderings must hash
        // identically or read-back would self-report tampering.
        assertThat(hasher.canonicalizeJson("{\"b\":2,\"a\":1}"))
                .isEqualTo(hasher.canonicalizeJson("{\"a\":1,\"b\":2}"));
        assertThat(hasher.canonicalizeJson(null)).isNull();
        assertThat(hasher.canonicalizeJson("  ")).isNull();
    }

    @Test
    void refusesMissingOrShortKey() {
        assertThatIllegalStateException().isThrownBy(() -> new AuditHasher(""))
                .withMessageContaining("AUDIT_HMAC_SECRET");
        assertThatIllegalStateException().isThrownBy(() -> new AuditHasher("too-short"));
    }
}
