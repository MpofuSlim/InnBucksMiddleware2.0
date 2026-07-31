package zw.co.innbucks.middleware.fineract;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreCapability;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.ClientResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.CommandResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.SavingsAccountResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.SavingsSummary;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.SavingsTransactionResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.TransactionSearchPage;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;

/**
 * {@link CoreBankingPort} on Apache Fineract. Key behaviours:
 *
 * <ul>
 *   <li><b>Resumable by positive verification, never by error-string
 *       sniffing:</b> every saga step that fails with a client rejection
 *       re-READS the resource's actual state and continues if the step
 *       already happened (a crashed earlier attempt). Duplicate-externalId
 *       on create → recover the existing resource by GET.</li>
 *   <li><b>Single conversion point:</b> {@link MinorUnits#toMajor()} at the
 *       port boundary; currency is cross-checked against the cell's
 *       configured currency before ANY write, and any amount echo Fineract
 *       returns is cross-checked after (mismatch → parked, never trusted).</li>
 *   <li><b>Reconciliation:</b> deposits/withdrawals always carry our
 *       external ref, so a 404-by-ref is a POSITIVE "never landed" → FAILED.
 *       Transfers cannot carry the ref in the create body, so an absent
 *       transfer is UNKNOWN — the row stays parked for the operator.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class FineractAdapter implements CoreBankingPort {

    private final FineractClient client;
    private final FineractProperties properties;

    @Override
    public CoreProvider provider() {
        return CoreProvider.FINERACT;
    }

    @Override
    public Set<CoreCapability> capabilities() {
        return Set.of(CoreCapability.SERVER_SIDE_DEDUP, CoreCapability.CLIENT_ASSIGNED_EXTERNAL_ID);
    }

    @Override
    public CoreCustomerRef createCustomer(CreateCustomerCommand cmd, IdempotencyKey key) {
        String externalId = cmd.requestedExternalId();
        if (externalId == null || externalId.isBlank()) {
            throw new CoreClientException(CoreProvider.FINERACT,
                    "Fineract requires a middleware-assigned externalId (CLIENT_ASSIGNED_EXTERNAL_ID)", null);
        }
        try {
            client.createClient(externalId, cmd.firstName(), cmd.lastName(), cmd.msisdn(), key.value());
            return new CoreCustomerRef(externalId);
        } catch (CoreClientException ex) {
            // Resume path: a previous attempt may have created the client
            // before dying. Positive verification — never error-text matching.
            ClientResponse existing = client.findClientByExternalId(externalId);
            if (existing != null) {
                log.info("createCustomer resume: Fineract client already exists externalId={}", externalId);
                return new CoreCustomerRef(externalId);
            }
            throw ex;
        }
    }

    /**
     * The savings onboarding saga: create → approve → activate, one
     * idempotency key per leg, every leg individually resumable. Re-invoking
     * after a partial failure picks up from the account's ACTUAL state.
     */
    @Override
    public AccountRef openDepositAccount(CoreCustomerRef customer, String requestedExternalId,
                                         IdempotencyKey key) {
        SavingsAccountResponse account = client.findSavingsByExternalId(requestedExternalId);
        Long savingsId;
        if (account == null) {
            ClientResponse owner = client.findClientByExternalId(customer.externalId());
            if (owner == null) {
                throw new CoreClientException(CoreProvider.FINERACT,
                        "Cannot open account: no Fineract client with externalId " + customer.externalId(), null);
            }
            try {
                CommandResponse created = client.createSavingsAccount(
                        owner.id(), requestedExternalId, key.value() + ":create");
                savingsId = created.resourceId() != null ? created.resourceId() : created.savingsId();
            } catch (CoreClientException ex) {
                // Crashed-after-create resume.
                SavingsAccountResponse existing = client.findSavingsByExternalId(requestedExternalId);
                if (existing == null) {
                    throw ex;
                }
                savingsId = existing.id();
            }
        } else {
            savingsId = account.id();
        }

        approveIfNeeded(savingsId, requestedExternalId, key);
        activateIfNeeded(savingsId, requestedExternalId, key);
        return new AccountRef(requestedExternalId);
    }

    private void approveIfNeeded(Long savingsId, String externalId, IdempotencyKey key) {
        try {
            client.approveSavingsAccount(savingsId, key.value() + ":approve");
        } catch (CoreClientException ex) {
            SavingsAccountResponse current = client.findSavingsByExternalId(externalId);
            if (current != null && current.status() != null && current.status().isApprovedOrActive()) {
                log.info("openDepositAccount resume: savings {} already approved", externalId);
                return;
            }
            throw ex;
        }
    }

    private void activateIfNeeded(Long savingsId, String externalId, IdempotencyKey key) {
        try {
            client.activateSavingsAccount(savingsId, key.value() + ":activate");
        } catch (CoreClientException ex) {
            SavingsAccountResponse current = client.findSavingsByExternalId(externalId);
            if (current != null && current.status() != null && current.status().isActive()) {
                log.info("openDepositAccount resume: savings {} already active", externalId);
                return;
            }
            throw ex;
        }
    }

    @Override
    public CustomerProfile getProfile(CoreCustomerRef ref) {
        ClientResponse response = client.findClientByExternalId(ref.externalId());
        if (response == null) {
            throw new CoreClientException(CoreProvider.FINERACT,
                    "No Fineract client with externalId " + ref.externalId(), null);
        }
        return new CustomerProfile(ref, response.firstname(), response.lastname(),
                Boolean.TRUE.equals(response.active()) ? "ACTIVE" : "INACTIVE");
    }

    @Override
    public List<DepositAccountSummary> listDepositAccounts(CoreCustomerRef ref) {
        var accounts = client.listClientAccounts(ref.externalId());
        List<DepositAccountSummary> result = new ArrayList<>();
        if (accounts == null || accounts.savingsAccounts() == null) {
            return result;
        }
        for (SavingsSummary summary : accounts.savingsAccounts()) {
            if (summary.externalId() == null || summary.externalId().isBlank()) {
                // Not middleware-managed (no stable ref) — invisible to the app.
                continue;
            }
            AccountBalance balance = getBalance(new AccountRef(summary.externalId()));
            result.add(new DepositAccountSummary(balance.account(), summary.productName(),
                    balance.current().currencyCode(), balance.available()));
        }
        return result;
    }

    @Override
    public AccountBalance getBalance(AccountRef account) {
        SavingsAccountResponse response = client.findSavingsByExternalId(account.externalId());
        if (response == null) {
            throw new CoreClientException(CoreProvider.FINERACT,
                    "No Fineract savings account with externalId " + account.externalId(), null);
        }
        String currency = response.currency() == null ? null : response.currency().code();
        if (currency == null || !currency.equals(properties.currency())) {
            // A wrong-currency account in this cell is a provisioning fault —
            // surfacing amounts under the wrong currency is a money bug.
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Savings " + account.externalId() + " currency " + currency
                            + " does not match the cell currency " + properties.currency(), null);
        }
        BigDecimal current = response.summary() == null || response.summary().accountBalance() == null
                ? BigDecimal.ZERO : response.summary().accountBalance();
        BigDecimal available = response.summary() == null || response.summary().availableBalance() == null
                ? current : response.summary().availableBalance();
        return new AccountBalance(account,
                MinorUnits.ofMajor(available, currency),
                MinorUnits.ofMajor(current, currency));
    }

    @Override
    public TransactionResult deposit(MoneyMovementCommand cmd, IdempotencyKey key) {
        requireCellCurrency(cmd.amount());
        CommandResponse response = client.deposit(cmd.account().externalId(),
                cmd.amount().toMajor(), cmd.externalRef().reference(), key.value());
        verifyAmountEcho(response, cmd.amount().toMajor(), cmd.externalRef());
        return new TransactionResult(coreRef(response, cmd.externalRef()), TransactionState.COMPLETED);
    }

    @Override
    public TransactionResult withdraw(MoneyMovementCommand cmd, IdempotencyKey key) {
        requireCellCurrency(cmd.amount());
        CommandResponse response = client.withdraw(cmd.account().externalId(),
                cmd.amount().toMajor(), cmd.externalRef().reference(), key.value());
        verifyAmountEcho(response, cmd.amount().toMajor(), cmd.externalRef());
        return new TransactionResult(coreRef(response, cmd.externalRef()), TransactionState.COMPLETED);
    }

    @Override
    public TransactionResult transfer(TransferCommand cmd, IdempotencyKey key) {
        requireCellCurrency(cmd.amount());
        SavingsAccountResponse from = requireSavings(cmd.from());
        SavingsAccountResponse to = requireSavings(cmd.to());
        CommandResponse response = client.transfer(from.clientId(), from.id(),
                to.clientId(), to.id(), cmd.amount().toMajor(), cmd.narrative(), key.value());
        return new TransactionResult(coreRef(response, cmd.externalRef()), TransactionState.COMPLETED);
    }

    @Override
    public TransactionResult getTransaction(TransactionLookup lookup) {
        return switch (lookup.kind()) {
            case DEPOSIT -> reconcileSavingsTransaction(lookup.destinationAccount(), lookup.externalRef());
            case WITHDRAWAL -> reconcileSavingsTransaction(lookup.sourceAccount(), lookup.externalRef());
            case TRANSFER -> reconcileTransfer(lookup.externalRef());
        };
    }

    private TransactionResult reconcileSavingsTransaction(AccountRef account, TxRef ref) {
        SavingsTransactionResponse txn = client.findSavingsTransaction(
                account.externalId(), ref.reference());
        if (txn == null) {
            // POSITIVE absence: our deposit/withdraw writes ALWAYS attach the
            // ref, so "no transaction with this ref" proves it never landed.
            return new TransactionResult(ref, TransactionState.FAILED);
        }
        if (Boolean.TRUE.equals(txn.reversed())) {
            return new TransactionResult(new TxRef(String.valueOf(txn.id())), TransactionState.FAILED);
        }
        return new TransactionResult(new TxRef(String.valueOf(txn.id())), TransactionState.COMPLETED);
    }

    private TransactionResult reconcileTransfer(TxRef ref) {
        var page = client.findTransfersByExternalId(ref.reference());
        if (page != null && page.hasMatch()) {
            return new TransactionResult(ref, TransactionState.COMPLETED);
        }
        // The transfer create body cannot carry our ref, so absence proves
        // nothing — the row stays parked for the operator. Never guess.
        return new TransactionResult(ref, TransactionState.UNKNOWN);
    }

    private SavingsAccountResponse requireSavings(AccountRef account) {
        SavingsAccountResponse response = client.findSavingsByExternalId(account.externalId());
        if (response == null) {
            throw new CoreClientException(CoreProvider.FINERACT,
                    "No Fineract savings account with externalId " + account.externalId(), null);
        }
        return response;
    }

    private void requireCellCurrency(MinorUnits amount) {
        if (!properties.currency().equals(amount.currencyCode())) {
            throw new CoreClientException(CoreProvider.FINERACT,
                    "Amount currency " + amount.currencyCode()
                            + " does not match the cell currency " + properties.currency(), null);
        }
    }

    @Override
    public TransactionPage listTransactions(TransactionHistoryQuery query) {
        TransactionSearchPage page = client.searchSavingsTransactions(
                query.account().externalId(), query.from(), query.to(), query.offset(), query.limit());
        if (page == null || page.pageItems() == null) {
            // A positive 404 on the account, or an empty page — an empty
            // statement is a legitimate answer, not a failure.
            return new TransactionPage(List.of(), 0);
        }
        String currency = properties.currency();
        List<TransactionEntry> entries = page.pageItems().stream()
                .map(t -> toEntry(t, currency))
                .toList();
        return new TransactionPage(entries, page.totalFilteredRecords());
    }

    private TransactionEntry toEntry(FineractDtos.SavingsTransaction t, String currency) {
        BigDecimal amount = t.amount() == null ? BigDecimal.ZERO : t.amount();
        return new TransactionEntry(
                String.valueOf(t.id()),
                // Present only when the movement came through us — Fineract
                // returns "" rather than null for an unset externalId.
                t.externalId() == null || t.externalId().isBlank() ? null : t.externalId(),
                direction(t),
                narrative(t),
                MinorUnits.ofMajor(amount, currency),
                t.runningBalance() == null ? null : MinorUnits.ofMajor(t.runningBalance(), currency),
                parseDate(t.date()),
                Boolean.TRUE.equals(t.reversed()));
    }

    /**
     * Direction from the ACCOUNT's side. {@code entryType} is authoritative
     * when present; the deposit/withdrawal booleans are the fallback for entry
     * types that predate it (interest posting, fees). Anything unrecognised is
     * treated as a DEBIT — on a statement, understating a credit is the safer
     * error.
     */
    private static TransactionDirection direction(FineractDtos.SavingsTransaction t) {
        if (t.entryType() != null) {
            return "CREDIT".equalsIgnoreCase(t.entryType())
                    ? TransactionDirection.CREDIT : TransactionDirection.DEBIT;
        }
        FineractDtos.TransactionTypeData type = t.transactionType();
        return type != null && Boolean.TRUE.equals(type.deposit())
                ? TransactionDirection.CREDIT : TransactionDirection.DEBIT;
    }

    private static String narrative(FineractDtos.SavingsTransaction t) {
        FineractDtos.TransactionTypeData type = t.transactionType();
        if (type == null) {
            return "Transaction";
        }
        return type.value() != null ? type.value()
                : (type.code() != null ? type.code() : "Transaction");
    }

    /**
     * Fineract's legacy Gson serializer emits LocalDate as a {@code [yyyy,m,d]}
     * ARRAY, not an ISO string. Both shapes are accepted so a serializer change
     * upstream doesn't silently break every statement.
     */
    private static LocalDate parseDate(Object raw) {
        if (raw == null) {
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Fineract returned a transaction with no date", null);
        }
        if (raw instanceof List<?> parts && parts.size() >= 3) {
            return LocalDate.of(asInt(parts.get(0)), asInt(parts.get(1)), asInt(parts.get(2)));
        }
        if (raw instanceof CharSequence text) {
            return LocalDate.parse(text);
        }
        throw new CoreServerException(CoreProvider.FINERACT,
                "Unrecognised transaction date shape from Fineract: " + raw, null);
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * The 100x guard's second half: when Fineract echoes the applied amount
     * back in the command response's {@code changes}, it MUST equal what we
     * sent. A mismatch means money moved in a shape we didn't ask for —
     * parked as unknown, never reported as success.
     */
    private void verifyAmountEcho(CommandResponse response, BigDecimal sent, TxRef ref) {
        Map<String, Object> changes = response == null ? null : response.changes();
        Object echoed = changes == null ? null : changes.get("transactionAmount");
        if (echoed == null) {
            return;
        }
        BigDecimal echo = new BigDecimal(String.valueOf(echoed));
        if (echo.compareTo(sent) != 0) {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, ref,
                    "Fineract echoed amount " + echo + " but " + sent + " was sent — parking for reconciliation", null);
        }
    }

    private static TxRef coreRef(CommandResponse response, TxRef fallback) {
        return response != null && response.resourceId() != null
                ? new TxRef(String.valueOf(response.resourceId()))
                : fallback;
    }
}
