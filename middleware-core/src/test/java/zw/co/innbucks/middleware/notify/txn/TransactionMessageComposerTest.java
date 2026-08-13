package zw.co.innbucks.middleware.notify.txn;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
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
 * The wording IS the contract — a customer reads this, a support agent has to
 * recognise it on the phone, and the owner signed off this exact voice after
 * reading the first live alerts. Exact-string assertions on purpose: a
 * template edit should have to be a conscious one.
 */
class TransactionMessageComposerTest {

    private static final String MINE = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet";   // hex tail 6e7f
    private static final String THEIRS = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet"; // hex tail 6c5d
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

    /** The core's customer-facing number rides in on the balance read. */
    private static AccountBalance balance(String externalId, long availableMinor, String accountNo) {
        return new AccountBalance(new AccountRef(externalId),
                new MinorUnits(availableMinor, "USD"), new MinorUnits(availableMinor, "USD"), accountNo);
    }

    @Test
    void depositOpensWithYourAccountAndTheNumericTail() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                balance(MINE, 2500, "000000010"), null, null);

        assertThat(body).isEqualTo("Your account ending 0010 has been credited with USD 25.00 "
                + "on 31-Jul-2026 at 14.05. Ref 00000012. Available balance USD 25.00.");
    }

    @Test
    void withdrawalReadsAsADebit() {
        String body = composer.compose(
                event(LedgerTransactionType.WITHDRAWAL, LedgerStatus.COMPLETED, MINE, null, 1000, null, "13"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.DEBIT),
                balance(MINE, 1500, "000000010"), null, null);

        assertThat(body).isEqualTo("Your account ending 0010 has been debited with USD 10.00 "
                + "on 31-Jul-2026 at 14.05. Ref 00000013. Available balance USD 15.00.");
    }

    @Test
    void outgoingTransferSaysYouSentWithBothNumericTails() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(CUSTOMER, MINE, THEIRS, TransactionDirection.DEBIT),
                balance(MINE, 1000, "000000010"), "000000021", null);

        assertThat(body).isEqualTo("You sent USD 5.00 from your account ending 0010 "
                + "to account ending 0021 on 31-Jul-2026 at 14.05. Ref 00000015. "
                + "Available balance USD 10.00.");
    }

    @Test
    void incomingTransferIsWrittenForTheRecipientNotTheSender() {
        // Same movement, other party: their account leads, the sender's is the
        // counterparty. Getting this backwards would tell someone they had
        // sent money they actually received.
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010", null);

        assertThat(body).isEqualTo("Your account ending 0021 has been credited with USD 5.00 "
                + "from account ending 0010 on 31-Jul-2026 at 14.05. Ref 00000015. "
                + "Available balance USD 30.00.");
    }

    @Test
    void anIncomingTransferNamesTheSenderRatherThanTheirAccount() {
        // Four digits of an account the recipient has never seen identify
        // nobody. The person who paid them is the useful fact.
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010", "T.Mpofu");

        assertThat(body).isEqualTo("Your account ending 0021 has been credited with USD 5.00 "
                + "from T.Mpofu on 31-Jul-2026 at 14.05. Ref 00000015. "
                + "Available balance USD 30.00.");
    }

    @Test
    void namingTheSenderNeverMakesTheMessageLongerThanNumberingThem() {
        // The budget invariant: a name is capped at the length of the phrase it
        // replaces, so switching to names can never cost a second SMS segment.
        String longestName = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010",
                TransactionMessageComposer.partyName("Tariro", "Chikwanhamutasania"));
        String numbered = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010", null);

        assertThat(longestName.length()).isLessThanOrEqualTo(numbered.length());
        assertThat(longestName).isEqualTo(SmsTextSanitizer.toGsmSafe(longestName));
    }

    @Test
    void anUnresolvableSenderFallsBackToTheAccountNeverToABlank() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010", null);

        assertThat(body).contains("from account ending 0010");
    }

    @Test
    void theSendersOwnMessageStillIdentifiesTheDestinationByAccount() {
        // They picked that destination seconds ago and saw the recipient's name
        // on the confirm screen; repeating it buys nothing and costs characters.
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500, null, "15"),
                new MovementLeg(CUSTOMER, MINE, THEIRS, TransactionDirection.DEBIT),
                balance(MINE, 1000, "000000010"), "000000021", null);

        assertThat(body).contains("to account ending 0021");
    }

    @Test
    void partyNameRendersInitialAndSurnameAndDegradesSafely() {
        assertThat(TransactionMessageComposer.partyName("Tariro", "Mpofu")).isEqualTo("T.Mpofu");
        // Lowercase input still yields a capital initial.
        assertThat(TransactionMessageComposer.partyName("tariro", "Mpofu")).isEqualTo("T.Mpofu");
        // Surname alone is still identifying; a first name alone is not.
        assertThat(TransactionMessageComposer.partyName(null, "Mpofu")).isEqualTo("Mpofu");
        assertThat(TransactionMessageComposer.partyName("Tariro", null)).isNull();
        assertThat(TransactionMessageComposer.partyName("  ", "  ")).isNull();
        // Capped so it can never outgrow "account ending 0000".
        assertThat(TransactionMessageComposer.partyName("Tariro", "Chikwanhamutasania"))
                .hasSizeLessThanOrEqualTo("account ending 0000".length());
    }

    @Test
    void aNameCarryingCharactersTheGatewayRejectsIsSanitisedNotDropped() {
        // The gateway 400s on ! : / ? " * ; — a surname containing one must not
        // take the whole alert down with it.
        String name = TransactionMessageComposer.partyName("Tariro", "O:Brien");

        assertThat(name).isNotNull();
        assertThat(name).isEqualTo(SmsTextSanitizer.toGsmSafe(name));
    }

    @Test
    void withoutAnAccountNumberTheTailDegradesToTheExternalIdNeverToNothing() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                balance(MINE, 2500, null), null, null);

        assertThat(body).startsWith("Your account ending 6e7f has been credited with USD 25.00");
    }

    @Test
    void anUnreadableBalanceCostsTheBalanceLineNotTheAlert() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                null, null, null);

        assertThat(body).isEqualTo("Your account ending 6e7f has been credited with USD 25.00 "
                + "on 31-Jul-2026 at 14.05. Ref 00000012.");
    }

    @Test
    void narrationRidesAlongWhenItFitsTheSegment() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 2500, "rent", "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                balance(MINE, 2500, "000000010"), null, null);

        assertThat(body).endsWith("Narration - rent.");
        assertThat(body.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void narrationIsDroppedRatherThanBillingASecondSegment() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED, MINE, THEIRS, 500,
                        "school fees for the term", "15"),
                new MovementLeg(UUID.randomUUID(), THEIRS, MINE, TransactionDirection.CREDIT),
                balance(THEIRS, 3000, "000000021"), "000000010", null);

        assertThat(body).doesNotContain("Narration");
        assertThat(body).contains("Ref 00000015.").contains("Available balance USD 30.00.");
        assertThat(body.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void failedMovementsSayPlainlyThatNothingMoved() {
        String body = composer.compose(
                event(LedgerTransactionType.TRANSFER, LedgerStatus.FAILED, MINE, THEIRS, 500, "rent", null),
                new MovementLeg(CUSTOMER, MINE, THEIRS, TransactionDirection.DEBIT),
                null, null, null);

        assertThat(body).isEqualTo("Your transfer of USD 5.00 from account ending 6e7f "
                + "to account ending 6c5d on 31-Jul-2026 at 14.05 was NOT successful. "
                + "Ref AAAAAAAA. No funds have moved.");
    }

    @Test
    void failedDepositAndWithdrawalKeepTheirOwnVerbs() {
        String deposit = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.FAILED, null, MINE, 2500, null, null),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT), null, null, null);
        String withdrawal = composer.compose(
                event(LedgerTransactionType.WITHDRAWAL, LedgerStatus.FAILED, MINE, null, 1000, null, null),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.DEBIT), null, null, null);

        assertThat(deposit).startsWith("Your deposit of USD 25.00 to account ending 6e7f")
                .endsWith("No funds have moved.");
        assertThat(withdrawal).startsWith("Your withdrawal of USD 10.00 from account ending 6e7f")
                .endsWith("No funds have moved.");
    }

    /**
     * The exact complaint set from the first live teller deposit, inverted:
     * numeric tail (not "ff61"), padded ref (not "Ref. 21"), balance line
     * present, no "Narration - Deposit", and "Your account" opening.
     */
    @Test
    void aCoreObservedDepositReadsExactlyLikeAnAppOne() {
        CoreMovementObserved movement = new CoreMovementObserved(
                MINE, TransactionDirection.CREDIT, new MinorUnits(60000, "USD"),
                null, null, "21", Instant.parse("2026-07-31T12:20:00Z"));

        String body = composer.composeObserved(movement, balance(MINE, 61500, "000000010"));

        assertThat(body).isEqualTo("Your account ending 0010 has been credited with USD 600.00 "
                + "on 31-Jul-2026 at 14.20. Ref 00000021. Available balance USD 615.00.");
    }

    @Test
    void aCoreObservedDebitUsesTheDebitVerb() {
        CoreMovementObserved movement = new CoreMovementObserved(
                MINE, TransactionDirection.DEBIT, new MinorUnits(1500, "USD"),
                null, null, "22", Instant.parse("2026-07-31T12:20:00Z"));

        String body = composer.composeObserved(movement, balance(MINE, 60000, "000000010"));

        assertThat(body).startsWith("Your account ending 0010 has been debited with USD 15.00");
    }

    @Test
    void anObservedMovementWithoutRefOrBalanceStillReadsCleanly() {
        CoreMovementObserved movement = new CoreMovementObserved(
                MINE, TransactionDirection.CREDIT, new MinorUnits(60000, "USD"),
                "Deposit", null, null, Instant.parse("2026-07-31T12:20:00Z"));

        String body = composer.composeObserved(movement, null);

        assertThat(body).isEqualTo("Your account ending 6e7f has been credited with USD 600.00 "
                + "on 31-Jul-2026 at 14.20.");
        // Even a narrative-bearing observed movement carries no narration
        // clause — the core's "narrative" is the transaction TYPE name.
        assertThat(body).doesNotContain("Narration");
    }

    @Test
    void numericRefsAreZeroPaddedAndOthersKeepTheirHead() {
        assertThat(TransactionMessageComposer.refFor("21")).isEqualTo("00000021");
        assertThat(TransactionMessageComposer.refFor("123456789")).isEqualTo("123456789");
        assertThat(TransactionMessageComposer.refFor("a".repeat(64))).isEqualTo("AAAAAAAA");
        assertThat(TransactionMessageComposer.refFor("  ")).isNull();
        assertThat(TransactionMessageComposer.refFor(null)).isNull();
    }

    @Test
    void tailsPreferTheNumericAccountNumber() {
        assertThat(TransactionMessageComposer.tail("000000010", MINE)).isEqualTo("0010");
        assertThat(TransactionMessageComposer.tail("21", MINE)).isEqualTo("21");
        assertThat(TransactionMessageComposer.tail(null, MINE)).isEqualTo("6e7f");
        assertThat(TransactionMessageComposer.tail("   ", MINE)).isEqualTo("6e7f");
    }

    @Test
    void largeAmountsAreGroupedTheSameWayOnEveryJvmLocale() {
        String body = composer.compose(
                event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, MINE, 123456789, null, "12"),
                new MovementLeg(CUSTOMER, MINE, null, TransactionDirection.CREDIT),
                balance(MINE, 123456789, "000000010"), null, null);

        assertThat(body).contains("USD 1,234,567.89");
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
                            balance(MINE, 2500, "000000010"), "000000021", null);
                    assertThat(SmsTextSanitizer.toGsmSafe(body))
                            .as("%s/%s/%s must need no sanitising", type, status, direction)
                            .isEqualTo(body);
                }
            }
        }
        for (TransactionDirection direction : TransactionDirection.values()) {
            String observed = composer.composeObserved(new CoreMovementObserved(
                    MINE, direction, new MinorUnits(60000, "USD"),
                    null, null, "21", Instant.parse("2026-07-31T12:20:00Z")),
                    balance(MINE, 61500, "000000010"));
            assertThat(SmsTextSanitizer.toGsmSafe(observed)).isEqualTo(observed);
        }
    }
}
