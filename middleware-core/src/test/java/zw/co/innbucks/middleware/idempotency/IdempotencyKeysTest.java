package zw.co.innbucks.middleware.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IdempotencyKeysTest {

    @Test
    void deterministicPerScopeAndKey() {
        assertThat(IdempotencyKeys.namespaced("customer-1", "key-A"))
                .isEqualTo(IdempotencyKeys.namespaced("customer-1", "key-A"))
                .hasSize(64)
                .matches("[0-9a-f]+");
    }

    @Test
    void sameRawKeyInDifferentScopesNeverCollides() {
        // The point: one customer's key can never replay another's response.
        assertThat(IdempotencyKeys.namespaced("customer-1", "key-A"))
                .isNotEqualTo(IdempotencyKeys.namespaced("customer-2", "key-A"));
    }

    @Test
    void scopeAndKeyBoundaryIsUnambiguous() {
        // Without a separator ("ab","c") and ("a","bc") would hash identically.
        assertThat(IdempotencyKeys.namespaced("ab", "c"))
                .isNotEqualTo(IdempotencyKeys.namespaced("a", "bc"));
    }

    @Test
    void rejectsBlankInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeys.namespaced("", "k"));
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeys.namespaced("s", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeys.namespaced(null, "k"));
    }
}
