package zw.co.innbucks.middleware.notify.txn;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.notify.SmsTextSanitizer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wording IS the contract here — a customer reads this, and a support
 * agent has to recognise it on the phone. Exact-string assertions are
 * deliberate: a template edit should have to be a conscious one.
 */
class TransactionMessageComposerTest {

    private static final String MINE = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet";   // -> 6e7f
    private static final String THEIRS = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet"; // -> 6c5d
    private static final UUID CUSTOMER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");

    /** Harare is UTC+2, so 12.05Z must render as 14.05 — the whole point of the zone. */
    private final TransactionMessageComposer composer =
            new TransactionMessageComposer(ZoneId.of("Africa/Harare"), 160, 24);

    private static SettledMovementEvent event(LedgerTransactionType type, LedgerStatus status,
                                              String source, String destination,
                                              long amountMinor, String narrative, String coreTxRef) {
        return new SettledMovementEvent(UUID.randomUUID(), CUSTOMER, type, status,
                source, destination, amountMinor, "USD", narrative,
                "a".repeat(64), coreTxRef, Instant.parse("2026-07-31T12:05:00Z"));
    }

    @Test
    void depositReadsAsACreditWithTheBalanceThatFollowedIt() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                new MinorUnits(2500, "USD"));

        assertThat(body).isEqualTo("InnBucks. Account ending 6e7f credited with USD 25.00 "
                + "on 31-Jul-2026 at 14.05. Ref. 12. Available balance USD 25.00.");
    }

    @Test
    void withdrawalReadsAsADebit() {
        String body = composer.compose(
                event(LedgerTransactionType.WITHDRAWAL, LedgerStatus.COMPLETED, MINE, null, 1000, null, "13"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.DEBIT),
                new MinorUnits(1500, "USD"));

        assertThat(body).isEqualTo("InnBucks. Account ending 6e7f debited with USD 10.00 "
                + "on 31-Jul-2026 at 14.05. Ref. 13. Available balance USD 15.00.");
    }

    @Test
    void outgoingTransferNamesBothSidesFromTheSendersPointOfView() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(CUSTOMER, MINE, THEIRS, TransactionDirection.DEBIT),
                new MinorUnits(1000, "USD"));

        assertThat(body).isEqualTo("InnBucks. Transfer of USD 5.00 sent from account ending 6e7f "
                + "to account ending 6c5d on 31-Jul-2026 at 14.05. Ref. 15. Available balance USD 10.00.");
    }

    @Test
    void incomingTransferIsWrittenForTheRECIPIENTNotTheSender() {
        // Same movement, other party: their account leads, the sender's is the
        // counterparty. Getting this backwards would tell someone they had
        // sent money they actually received.
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                new MinorUnits(3000, "USD"));

        assertThat(body).isEqualTo("InnBucks. Account ending 6c5d credited with USD 5.00 "
                + "from account ending 6e7f on 31-Jul-2026 at 14.05. Ref. 15. Available balance USD 30.00.");
    }

    @Test
    void anUnreadableBalanceCostsTheBalanceLineNotTheAlert() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                null);

        assertThat(body).isEqualTo("InnBucks. Account ending 6e7f credited with USD 25.00 "
                + "on 31-Jul-2026 at 14.05. Ref. 12.");
    }

    @Test
    void narrationRidesAlongWhenItFitsTheSegment() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, "rent", "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                new MinorUnits(3000, "USD"));

        assertThat(body).endsWith("Narration - rent.");
        assertThat(body.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void narrationIsDroppedRatherThanBillingASecondSegment() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500,
                        "school fees for the term", "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                new MinorUnits(3000, "USD"));

        assertThat(body).doesNotContain("Narration");
        assertThat(body).contains("Ref. 15.").contains("Available balance USD 30.00.");
        assertThat(body.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void narrationIsTruncatedToItsBudgetBeforeTheLengthCheck() {
        TransactionMessageComposer roomy = new TransactionMessageComposer(ZoneId.of("UTC"), 400, 10);

        String body = roomy.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500,
                        "abcdefghijKLMNOPQRST", "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                new MinorUnits(2500, "USD"));

        assertThat(body).endsWith("Narration - abcdefghij.");
    }

    @Test
    void failedMovementsSayPlainlyThatNothingMoved() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.FAILED, MINE, THEIRS, 500, "rent", null),
                new MovementLeg(CUSTOMER, MINE, THEIRS, TransactionDirection.DEBIT),
                null);

        assertThat(body).isEqualTo("InnBucks. Transfer of USD 5.00 from account ending 6e7f "
                + "to account ending 6c5d on 31-Jul-2026 at 14.05 was NOT successful. "
                + "Ref. AAAAAAAA. No funds have moved.");
    }

    @Test
    void failedDepositAndWithdrawalKeepTheirOwnVerbs() {
        String deposit = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.FAILED, null, MINE, 2500, null, null),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT), null);
        String withdrawal = composer.compose(
                event(LedgerTransactionType.WITHDRAWAL, LedgerStatus.FAILED, MINE, null, 1000, null, null),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.DEBIT), null);

        assertThat(deposit).contains("Deposit of USD 25.00 to account ending 6e7f")
                .endsWith("No funds have moved.");
        assertThat(withdrawal).contains("Withdrawal of USD 10.00 from account ending 6e7f")
                .endsWith("No funds have moved.");
    }

    @Test
    void withoutACoreReferenceItFallsBackToTheHeadOfOurOwnRef() {
        // The full external ref is a 64-char SHA-256 digest — unreadable over
        // SMS, and useless to quote to support.
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, null),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT), null);

        assertThat(body).contains("Ref. AAAAAAAA.");
    }

    @Test
    void largeAmountsAreGroupedTheSameWayOnEveryJvmLocale() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 123456789, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                new MinorUnits(123456789, "USD"));

        assertThat(body).contains("USD 1,234,567.89");
    }

    @Test
    void aCoreObservedCreditReadsLikeAnyOtherCredit() {
        String body = composer.composeObserved(new zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved(
                MINE, TransactionDirection.CREDIT, new MinorUnits(60000, "USD"),
                "Deposit", null, "42", Instant.parse("2026-07-31T12:05:00Z")));

        assertThat(body).isEqualTo("InnBucks. Account ending 6e7f credited with USD 600.00 "
                + "on 31-Jul-2026 at 14.05. Ref. 42. Narration - Deposit.");
    }

    @Test
    void aCoreObservedDebitSaysDebitedAndSurvivesAMissingRef() {
        String body = composer.composeObserved(new zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved(
                MINE, TransactionDirection.DEBIT, new MinorUnits(1500, "USD"),
                null, null, null, Instant.parse("2026-07-31T12:05:00Z")));

        assertThat(body).isEqualTo("InnBucks. Account ending 6e7f debited with USD 15.00 "
                + "on 31-Jul-2026 at 14.05.");
    }

    @Test
    void observedCopySurvivesTheGatewaysCharsetUntouched() {
        for (TransactionDirection direction : TransactionDirection.values()) {
            String body = composer.composeObserved(new zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved(
                    MINE, direction, new MinorUnits(123456789, "USD"),
                    "Interest Posting", null, "42", Instant.parse("2026-07-31T12:05:00Z")));
            assertThat(SmsTextSanitizer.toGsmSafe(body)).isEqualTo(body);
            assertThat(body.length()).isLessThanOrEqualTo(160);
        }
    }

    /**
     * The gateway 400s on {@code ! : / ? " * ;}. Every template must already be
     * inside its whitelist — this fails the moment someone writes {@code Ref:}
     * or a {@code 14:05} timestamp back into the copy.
     */
    @Test
    void everyTemplateSurvivesTheGatewaysCharsetUntouched() {
        for (LedgerTransactionType type : LedgerTransactionType.values()) {
            for (LedgerStatus status : new LedgerStatus[]{LedgerStatus.COMPLETED, LedgerStatus.FAILED}) {
                for (TransactionDirection direction : TransactionDirection.values()) {
                    String body = composer.compose(
                            event(type, status, MINE, THEIRS, 123456789, "rent", "12"),
                            new MovementLeg(CUSTOMER, MINE, THEIRS, direction),
                            new MinorUnits(2500, "USD"));
                    assertThat(SmsTextSanitizer.toGsmSafe(body))
                            .as("%s/%s/%s must need no sanitising", type, status, direction)
                            .isEqualTo(body);
                }
            }
        }
    }
}
