package zw.co.innbucks.middleware.me;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;

import java.util.List;
import java.util.UUID;

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
