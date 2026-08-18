package zw.co.innbucks.middleware.notify.txn;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreMovementListener;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.otp.SmsSender;

import java.util.UUID;

/**
 * Alerts a customer about a movement the CORE reports — the path for money
 * that moved without this middleware's involvement (teller/admin postings,
 * interest, corrections). The app-initiated path stays where it is, on
 * {@code LedgerService.transition()}; this class exists for everything that
 * path structurally cannot see.
 *
 * <p><b>Dedup is by reference, and it is the load-bearing part.</b> Every
 * movement this middleware initiates attaches its ledger {@code external_ref}
 * to the core transaction, and the core echoes it back in the event. So: a
 * reference we recognise in our ledger = a movement whose settlement already
 * alerts through the ledger seam — dropped here, or the customer gets two
 * messages for one deposit. No reference (or one we did not mint) = the core
 * acted on its own = exactly the case this class exists for.
 *
 * <p>The account→customer resolution follows the transaction-notifier's rule:
 * the {@code <customerUuid>:...} naming is only a CANDIDATE, confirmed against
 * the core's own account list before anything is sent. A hook payload is
 * still external input even with a valid shared secret.
 *
 * <p>Failures never propagate — the caller is a webhook endpoint whose 2xx/4xx
 * tells the CORE whether to retry, and a dead SMS gateway is not the core's
 * problem. Same fire-and-forget posture as {@link TransactionNotifier}.
 */
@Slf4j
@Service
public class CoreMovementAlertService implements CoreMovementListener {

    private final TransactionNotificationProperties properties;
    private final TransactionMessageComposer composer;
    private final CustomerRepository customerRepository;
    private final LedgerTransactionRepository ledgerRepository;
    private final ObjectProvider<CoreBankingPort> corePort;
    private final SmsSender smsSender;
    private final MeterRegistry meterRegistry;

    public CoreMovementAlertService(TransactionNotificationProperties properties,
                                    TransactionMessageComposer composer,
                                    CustomerRepository customerRepository,
                                    LedgerTransactionRepository ledgerRepository,
                                    ObjectProvider<CoreBankingPort> corePort,
                                    SmsSender smsSender,
                                    MeterRegistry meterRegistry) {
        this.properties = properties;
        this.composer = composer;
        this.customerRepository = customerRepository;
        this.ledgerRepository = ledgerRepository;
        this.corePort = corePort;
        this.smsSender = smsSender;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return what happened, for the webhook endpoint's response + logs. Every
     *         outcome except SENT/FAILED is a deliberate drop, each counted
     *         under its own reason so a misconfigured hook is visible in
     *         metrics rather than as silent customer complaints.
     */
    @Override
    public void onCoreMovement(CoreMovementObserved movement) {
        alert(movement);
    }

    public Outcome alert(CoreMovementObserved movement) {
        if (!properties.enabled()) {
            return count(Outcome.DISABLED, movement);
        }
        // OUR ref echoed back = the ledger seam already owns this alert.
        if (movement.hasOurReference()
                && ledgerRepository.findByExternalRef(movement.externalRef()).isPresent()) {
            return count(Outcome.DEDUPED_OURS, movement);
        }
        UUID candidate = customerUuidPrefix(movement.accountExternalId());
        if (candidate == null) {
            return count(Outcome.NO_CUSTOMER_MAPPING, movement);
        }
        Customer customer = customerRepository.findById(candidate).orElse(null);
        if (customer == null || customer.getCoreExternalId() == null) {
            return count(Outcome.NO_CUSTOMER_MAPPING, movement);
        }
        if (customer.getMsisdn() == null || customer.getMsisdn().isBlank()) {
            return count(Outcome.NO_MSISDN, movement);
        }
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null) {
            return count(Outcome.NO_CORE_PORT, movement);
        }
        boolean owns;
        try {
            owns = port.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()))
                    .stream()
                    .anyMatch(a -> a.account().externalId().equals(movement.accountExternalId()));
        } catch (RuntimeException ex) {
            // Cannot PROVE ownership -> nothing is sent. A missed courtesy SMS
            // beats texting one customer about another customer's money.
            log.warn("Ownership check failed for core-reported movement on account {} — no alert: {}",
                    AccountMasking.maskAccount(movement.accountExternalId()), ex.toString());
            return count(Outcome.OWNERSHIP_UNPROVEN, movement);
        }
        if (!owns) {
            log.warn("Account {} matches customer {} by naming convention but the core does not list "
                            + "it for them — no core-movement alert sent",
                    AccountMasking.maskAccount(movement.accountExternalId()), candidate);
            return count(Outcome.OWNERSHIP_UNPROVEN, movement);
        }
        // The balance is a courtesy; failure to read it costs the message its
        // balance line and numeric tail, never the alert itself.
        AccountBalance balance = null;
        try {
            balance = port.getBalance(new zw.co.innbucks.middleware.corebanking.value.AccountRef(
                    movement.accountExternalId()));
        } catch (RuntimeException ex) {
            log.warn("Balance read failed for core-reported movement on account {} — alerting without it: {}",
                    AccountMasking.maskAccount(movement.accountExternalId()), ex.toString());
        }
        try {
            smsSender.send(customer.getMsisdn(), composer.composeObserved(movement, balance));
            log.info("Core-movement alert sent to {} for account {} coreTxRef={}",
                    MsisdnMasking.mask(customer.getMsisdn()),
                    AccountMasking.maskAccount(movement.accountExternalId()), movement.coreTxRef());
            return count(Outcome.SENT, movement);
        } catch (RuntimeException ex) {
            log.warn("Core-movement alert delivery failed for account {} coreTxRef={}: {}",
                    AccountMasking.maskAccount(movement.accountExternalId()),
                    movement.coreTxRef(), ex.toString());
            return count(Outcome.FAILED, movement);
        }
    }

    private static UUID customerUuidPrefix(String accountExternalId) {
        int slot = accountExternalId.indexOf(':');
        String head = slot > 0 ? accountExternalId.substring(0, slot) : accountExternalId;
        try {
            return UUID.fromString(head.trim());
        } catch (IllegalArgumentException notOurConvention) {
            return null;
        }
    }

    private Outcome count(Outcome outcome, CoreMovementObserved movement) {
        Counter.builder("innbucks.transaction.notifications")
                .description("Customer transaction alerts, by movement type, leg and delivery outcome")
                .tag("type", "CORE_" + movement.direction().name())
                .tag("leg", movement.direction().name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", outcome.metricValue)
                .register(meterRegistry)
                .increment();
        return outcome;
    }

    public enum Outcome {
        SENT("sent"),
        FAILED("failed"),
        DISABLED("disabled"),
        DEDUPED_OURS("deduped_ours"),
        NO_CUSTOMER_MAPPING("skipped_no_customer"),
        NO_MSISDN("skipped_no_msisdn"),
        NO_CORE_PORT("skipped_no_port"),
        OWNERSHIP_UNPROVEN("skipped_ownership_unproven");

        final String metricValue;

        Outcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }
}
