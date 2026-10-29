package zw.co.innbucks.middleware.corebanking.value;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which directions each restriction stops. The payment path refuses a debit on
 * {@link AccountRestriction#blocksDebits()}, so a full block that read as
 * "debits allowed" would wave a frozen wallet's payment through to the core.
 */
class AccountRestrictionTest {

    @Test
    void aFullBlockStopsBothDirections() {
        assertThat(AccountRestriction.FULLY_BLOCKED.blocksDebits()).isTrue();
        assertThat(AccountRestriction.FULLY_BLOCKED.blocksCredits()).isTrue();
    }

    @Test
    void aDebitBlockStopsPaymentsOutOnly() {
        assertThat(AccountRestriction.DEBITS_BLOCKED.blocksDebits()).isTrue();
        assertThat(AccountRestriction.DEBITS_BLOCKED.blocksCredits()).isFalse();
    }

    @Test
    void aCreditBlockStopsMoneyInOnly() {
        assertThat(AccountRestriction.CREDITS_BLOCKED.blocksDebits()).isFalse();
        assertThat(AccountRestriction.CREDITS_BLOCKED.blocksCredits()).isTrue();
    }

    @Test
    void noneStopsNothing() {
        assertThat(AccountRestriction.NONE.blocksDebits()).isFalse();
        assertThat(AccountRestriction.NONE.blocksCredits()).isFalse();
    }
}
