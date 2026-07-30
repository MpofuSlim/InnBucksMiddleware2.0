package zw.co.innbucks.middleware.stepup;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TxnFingerprintTest {

    private static final UUID CUSTOMER = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");

    @Test
    void deterministicAndHexShaped() {
        String a = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dst", 60000, "KES");
        String b = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dst", 60000, "KES");
        assertThat(a).isEqualTo(b).matches("^[0-9a-f]{64}$");
    }

    @Test
    void everyFieldChangesTheFingerprint() {
        String base = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dst", 60000, "KES");
        assertThat(TxnFingerprint.of(OTHER, LedgerTransactionType.TRANSFER, "src", "dst", 60000, "KES"))
                .isNotEqualTo(base);
        assertThat(TxnFingerprint.of(CUSTOMER, LedgerTransactionType.WITHDRAWAL, "src", "dst", 60000, "KES"))
                .isNotEqualTo(base);
        assertThat(TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "srcX", "dst", 60000, "KES"))
                .isNotEqualTo(base);
        assertThat(TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dstX", 60000, "KES"))
                .isNotEqualTo(base);
        assertThat(TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dst", 60001, "KES"))
                .isNotEqualTo(base);
        assertThat(TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "src", "dst", 60000, "USD"))
                .isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeGamed() {
        // Without the 0x1F separator ("ab","c") and ("a","bc") would collide.
        String ab_c = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "ab", "c", 1, "KES");
        String a_bc = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.TRANSFER, "a", "bc", 1, "KES");
        assertThat(ab_c).isNotEqualTo(a_bc);
    }

    @Test
    void nullSideAccountsAreStable() {
        String a = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.WITHDRAWAL, "src", null, 1, "KES");
        String b = TxnFingerprint.of(CUSTOMER, LedgerTransactionType.WITHDRAWAL, "src", null, 1, "KES");
        assertThat(a).isEqualTo(b);
    }
}
