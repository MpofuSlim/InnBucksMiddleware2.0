package zw.co.innbucks.middleware.me;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import zw.co.innbucks.middleware.transactions.AccountOwnershipException;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;

/**
 * Current-customer reads: the local row is the identity anchor (status, KYC
 * tier, MSISDN); names/accounts/balances come from the core via the port.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CustomerRepository customerRepository;
    private final CoreBankingPort corePort;

    public ProfileResponse profileFor(UUID customerId) {
        Customer customer = requireMappedCustomer(customerId);
        CustomerProfile core = corePort.getProfile(new CoreCustomerRef(customer.getCoreExternalId()));
        return new ProfileResponse(
                customer.getId(),
                customer.getMsisdn(),
                core.firstName(),
                core.lastName(),
                customer.getStatus(),
                customer.getKycTier());
    }

    public List<AccountView> accountsFor(UUID customerId) {
        Customer customer = requireMappedCustomer(customerId);
        List<DepositAccountSummary> accounts =
                corePort.listDepositAccounts(new CoreCustomerRef(customer.getCoreExternalId()));
        return accounts.stream()
                .map(a -> new AccountView(a.account().externalId(), a.name(),
                        a.currencyCode(), a.balance().amount()))
                .toList();
    }

    /**
     * A page of the customer's statement, sourced from the CORE.
     *
     * <p>Deliberately not from our local ledger: an account also accrues
     * interest postings, fees and any teller correction that never crossed
     * this middleware, and a statement missing those would not reconcile
     * against the balance the customer can see. The trade-off is that a
     * movement we have parked as UNKNOWN has no confirmed core entry yet and
     * so will not appear until it reconciles.
     */
    public StatementView statementFor(UUID customerId, String accountId,
                                      LocalDate from, LocalDate to, int offset, int limit) {
        Customer customer = requireMappedCustomer(customerId);
        DepositAccountRef account = requireOwnedAccount(customer, accountId);

        TransactionPage page = corePort.listTransactions(
                new TransactionHistoryQuery(account.account(), from, to, offset, limit));

        List<StatementEntryView> entries = page.entries().stream()
                .map(e -> new StatementEntryView(
                        e.coreId(),
                        e.externalRef(),
                        e.direction().name(),
                        e.narrative(),
                        e.amount().amount(),
                        e.runningBalance() == null ? null : e.runningBalance().amount(),
                        e.valueDate(),
                        e.reversed()))
                .toList();

        return new StatementView(accountId, account.currencyCode(), entries,
                page.totalCount(), offset, limit);
    }

    /**
     * Ownership against the CORE's account list — the same source of truth the
     * movement endpoints use, never a local cache. Returns the account so the
     * caller gets its currency without a second round trip.
     */
    private DepositAccountRef requireOwnedAccount(Customer customer, String accountId) {
        return corePort.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()))
                .stream()
                .filter(a -> a.account().externalId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new AccountOwnershipException(accountId));
    }

    private Customer requireMappedCustomer(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        if (customer.getCoreExternalId() == null) {
            // Registration crashed before the core mapping landed; the app
            // should re-drive POST /register (idempotent + resumable).
            throw new ProfileNotFoundException(customerId);
        }
        return customer;
    }
}
