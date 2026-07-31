package zw.co.innbucks.middleware.fineract;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FineractIdempotencyKeyTest {

    /** What IdempotencyKeys.namespaced actually produces: 64 hex chars. */
    private static String namespacedKey(String seed) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
    }

    @Test
    void fitsFineractsColumnForARealNamespacedKeyPlusTheLongestSagaSuffix() throws Exception {
        String worstCase = namespacedKey("customer-1") + ":activate";
        assertThat(worstCase.length()).isGreaterThan(FineractIdempotencyKey.MAX_LENGTH); // the bug

        assertThat(FineractIdempotencyKey.forCore(worstCase))
                .hasSizeLessThanOrEqualTo(FineractIdempotencyKey.MAX_LENGTH);
    }

    @Test
    void isDeterministicSoARetryDedupsUpstream() {
        assertThat(FineractIdempotencyKey.forCore("key-abc"))
                .isEqualTo(FineractIdempotencyKey.forCore("key-abc"));
    }

    @Test
    void keepsTheSagaLegsDistinct() throws Exception {
        // Truncation would collapse these — the suffix is at the END of a
        // 64-char key, past any cut that fits in 50 characters.
        String base = namespacedKey("customer-1");
        String create = FineractIdempotencyKey.forCore(base + ":create");
        String approve = FineractIdempotencyKey.forCore(base + ":approve");
        String activate = FineractIdempotencyKey.forCore(base + ":activate");

        assertThat(create).isNotEqualTo(approve).isNotEqualTo(activate);
        assertThat(approve).isNotEqualTo(activate);
    }

    @Test
    void differentCustomersNeverShareAKey() throws Exception {
        assertThat(FineractIdempotencyKey.forCore(namespacedKey("customer-1")))
                .isNotEqualTo(FineractIdempotencyKey.forCore(namespacedKey("customer-2")));
    }

    @Test
    void isUrlAndHeaderSafe() throws Exception {
        assertThat(FineractIdempotencyKey.forCore(namespacedKey("customer-1")))
                .matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> FineractIdempotencyKey.forCore(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FineractIdempotencyKey.forCore("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
