package zw.co.innbucks.middleware.corebanking.value;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two addressings are two keys to one account, and a query must carry
 * exactly one — a query with both (or neither) has no defined meaning and an
 * adapter must never be left to pick.
 */
class TransactionHistoryQueryTest {

    private static final AccountRef REF = new AccountRef("cust-1:wallet");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Test
    void externalRefAddressingKeepsItsOriginalShape() {
        TransactionHistoryQuery query = new TransactionHistoryQuery(REF, FROM, TO, 0, 20);
        assertThat(query.account()).isEqualTo(REF);
        assertThat(query.coreAccountId()).isNull();
    }

    @Test
    void coreAccountIdAddressingCarriesNoExternalRef() {
        TransactionHistoryQuery query = TransactionHistoryQuery.byCoreAccountId("23", FROM, TO, 0, 20);
        assertThat(query.coreAccountId()).isEqualTo("23");
        assertThat(query.account()).isNull();
    }

    @Test
    void bothAddressingsAtOnceAreRefused() {
        assertThatThrownBy(() -> new TransactionHistoryQuery(REF, "23", FROM, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neitherAddressingIsRefused() {
        assertThatThrownBy(() -> new TransactionHistoryQuery(null, null, FROM, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCoreAccountIdIsRefused() {
        assertThatThrownBy(() -> TransactionHistoryQuery.byCoreAccountId(" ", FROM, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsStillApplyToBothAddressings() {
        assertThatThrownBy(() -> TransactionHistoryQuery.byCoreAccountId("23", FROM, TO, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHistoryQuery.byCoreAccountId("23", FROM, TO, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHistoryQuery.byCoreAccountId("23", TO, FROM, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
