package zw.co.innbucks.middleware.notify.txn;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerName;
import zw.co.innbucks.middleware.customer.CustomerNameResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.otp.SmsSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tells customers their money moved. One SMS per {@link MovementLeg}: the
 * initiator of every deposit / withdrawal / transfer, PLUS the recipient of an
 * incoming transfer when the destination account belongs to a customer of this
 * cell.
 *
 * <p><b>This is a side-effect, and it is subordinate to the movement.</b> Two
 * rules make that structural rather than aspirational:
 *
 * <ul>
 *   <li><b>{@link TransactionPhase#AFTER_COMMIT}</b> — the ledger row is
 *       durably terminal before a single message is composed, so no customer
 *       is ever told about a movement that then rolled back. (Deliberately the
 *       OPPOSITE posture to the OTP path, where a delivery failure DOES roll
 *       the challenge back: there, an undelivered code makes the row useless;
 *       here, the money has already moved and an undelivered receipt cannot
 *       un-move it.)</li>
 *   <li><b>Nothing escapes this class.</b> Every failure — customer gone,
 *       core unreachable, gateway down — is caught, counted and logged. An
 *       exception thrown from an after-commit callback propagates to the
 *       caller of {@code commit()}, which would turn "the SMS gateway is
 *       having a bad day" into a failed-looking deposit. It must not.</li>
 * </ul>
 *
 * <p>Delivery is fire-and-forget on a bounded pool: a crash between commit and
 * send loses the message, and that is the accepted trade. The ledger, the
 * audit chain and {@code /me/accounts/{id}/transactions} are the record of
 * what happened; this is a courtesy on top of them.
 *
 * <p>Watch {@code innbucks.transaction.notifications} — {@code outcome=failed}
 * rising means customers are moving money blind.
 */
@Slf4j
@Component
public class TransactionNotifier {

    private final TransactionNotificationProperties properties;
    private final TransactionMessageComposer composer;
    private final CustomerRepository customerRepository;
    private final ObjectProvider<CoreBankingPort> corePort;
    private final CustomerNameResolver nameResolver;
    private final SmsSender smsSender;
    private final MeterRegistry meterRegistry;

    public TransactionNotifier(TransactionNotificationProperties properties,
                               TransactionMessageComposer composer,
                               CustomerRepository customerRepository,
                               ObjectProvider<CoreBankingPort> corePort,
                               CustomerNameResolver nameResolver,
                               SmsSender smsSender,
                               MeterRegistry meterRegistry) {
        this.properties = properties;
        this.composer = composer;
        this.customerRepository = customerRepository;
        this.corePort = corePort;
        this.nameResolver = nameResolver;
        this.smsSender = smsSender;
        this.meterRegistry = meterRegistry;
    }

    @Async("transactionNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettled(SettledMovementEvent event) {
        try {
            deliver(event);
        } catch (RuntimeException ex) {
            // Belt to the per-leg braces below. Whatever happens here, the
            // movement itself is already committed and correct.
            counter(event, "unknown", "failed").increment();
            log.warn("Transaction notification aborted for ledger row id={} externalRef={}: {}",
                    event.transactionId(), event.externalRef(), ex.toString());
        }
    }

    /** Package-private seam so tests drive the real work without an async hop. */
    void deliver(SettledMovementEvent event) {
        if (!properties.enabled()) {
            return;
        }
        if (!event.completed() && !properties.notifyOnFailure()) {
            return;
        }
        for (MovementLeg leg : legsOf(event)) {
            notifyLeg(event, leg);
        }
    }

    /**
     * Who hears about this movement. The initiator always; the recipient of a
     * COMPLETED transfer additionally, when we can prove the destination
     * account is theirs.
     *
     * <p>No credit leg on a FAILED transfer — nothing arrived, so there is
     * nothing to tell the other party, and a "your transfer failed" SMS to
     * someone who was never expecting money is pure confusion.
     */
    private List<MovementLeg> legsOf(SettledMovementEvent event) {
        List<MovementLeg> legs = new ArrayList<>(2);
        switch (event.type()) {
            case DEPOSIT -> legs.add(new MovementLeg(
                    event.customerId(), event.destinationAccount(), null, TransactionDirection.CREDIT));
            case WITHDRAWAL -> legs.add(new MovementLeg(
                    event.customerId(), event.sourceAccount(), null, TransactionDirection.DEBIT));
            case TRANSFER -> {
                legs.add(new MovementLeg(event.customerId(), event.sourceAccount(),
                        event.destinationAccount(), TransactionDirection.DEBIT));
                if (event.completed()) {
                    recipientOf(event).ifPresent(recipient -> legs.add(new MovementLeg(
                            recipient, event.destinationAccount(),
                            event.sourceAccount(), TransactionDirection.CREDIT)));
                }
            }
        }
        return legs;
    }

    /**
     * The customer on the receiving end of a transfer, when there is one.
     *
     * <p>Two steps, and the second is not optional. The middleware mints
     * wallet externalIds as {@code <customerUuid>:wallet}, so the UUID prefix
     * is a CANDIDATE — but acting on a naming convention alone would let a
     * same-shaped account that isn't ours send one customer's amounts to
     * another customer's phone. So the candidate is confirmed against the
     * core's own account list for that customer before anything is sent.
     *
     * <p>Empty is the normal answer for a transfer out of the cell, and for
     * any core that assigns its own account externalIds (no
     * {@code CLIENT_ASSIGNED_EXTERNAL_ID}) — such a core will need a persisted
     * account→customer map here, not a convention.
     */
    private Optional<UUID> recipientOf(SettledMovementEvent event) {
        UUID candidate = customerUuidPrefix(event.destinationAccount());
        if (candidate == null || candidate.equals(event.customerId())) {
            // Same customer moving between their own accounts: the debit
            // message already names both sides. One movement, one SMS.
            return Optional.empty();
        }
        Customer customer = customerRepository.findById(candidate).orElse(null);
        if (customer == null || customer.getCoreExternalId() == null) {
            return Optional.empty();
        }
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null) {
            return Optional.empty();
        }
        boolean owns = port.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()))
                .stream()
                .anyMatch(a -> a.account().externalId().equals(event.destinationAccount()));
        if (!owns) {
            log.warn("Destination account {} looks like customer {}'s by naming convention but the core "
                            + "does not list it for them — no incoming-transfer alert sent",
                    AccountMasking.maskAccount(event.destinationAccount()), candidate);
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static UUID customerUuidPrefix(String accountExternalId) {
        if (accountExternalId == null || accountExternalId.isBlank()) {
            return null;
        }
        int slot = accountExternalId.indexOf(':');
        String head = slot > 0 ? accountExternalId.substring(0, slot) : accountExternalId;
        try {
            return UUID.fromString(head.trim());
        } catch (IllegalArgumentException notOurConvention) {
            return null;
        }
    }

    private void notifyLeg(SettledMovementEvent event, MovementLeg leg) {
        String direction = leg.direction().name().toLowerCase(java.util.Locale.ROOT);
        try {
            Customer customer = customerRepository.findById(leg.customerId()).orElse(null);
            if (customer == null || customer.getMsisdn() == null || customer.getMsisdn().isBlank()) {
                counter(event, direction, "skipped_no_msisdn").increment();
                log.warn("No MSISDN for customer {} — transaction alert skipped for ledger row id={}",
                        leg.customerId(), event.transactionId());
                return;
            }
            AccountBalance balance = event.completed() ? balanceOf(leg.accountId()) : null;
            // The other side's customer-facing account number, for the message
            // tail only — its balance is read but NEVER rendered here.
            String counterpartyNumber = event.completed() && leg.counterparty() != null
                    ? accountNumberOf(leg.counterparty()) : null;
            String counterpartyName = senderNameFor(event, leg);
            String body = composer.compose(event, leg, balance, counterpartyNumber, counterpartyName);
            smsSender.send(customer.getMsisdn(), body);
            counter(event, direction, "sent").increment();
            log.info("Transaction alert sent to {} for ledger row id={} type={} leg={}",
                    MsisdnMasking.mask(customer.getMsisdn()), event.transactionId(), event.type(), direction);
        } catch (RuntimeException ex) {
            counter(event, direction, "failed").increment();
            // Never the message body and never the MSISDN in the failure line.
            log.warn("Transaction alert delivery failed for ledger row id={} type={} leg={}: {}",
                    event.transactionId(), event.type(), direction, ex.toString());
        }
    }

    /**
     * The SENDER's name, for the recipient's side of a completed transfer —
     * "credited with USD 5.00 from T.Mpofu" rather than "from account ending
     * 0010". Four digits of an account the recipient has never seen identify
     * nobody; the person who paid them is what they actually want to know.
     *
     * <p>Only this leg. The sender's own message still names the destination by
     * account, because the sender chose that destination moments earlier and
     * already saw the recipient's name on the confirm screen.
     *
     * <p>The sender is {@code event.customerId()} — the movement's initiator —
     * so no extra lookup is needed to find WHO, only to find their name, which
     * lives in the core (this middleware stores no names). Best-effort: any
     * failure returns null and the sentence falls back to the account phrase.
     */
    private String senderNameFor(SettledMovementEvent event, MovementLeg leg) {
        if (!event.completed()
                || event.type() != LedgerTransactionType.TRANSFER
                || leg.direction() != TransactionDirection.CREDIT) {
            return null;
        }
        try {
            Customer sender = customerRepository.findById(event.customerId()).orElse(null);
            if (sender == null || sender.getCoreExternalId() == null) {
                return null;
            }
            // Cached (short TTL) — the sender's name is the same string on
            // every transfer they make, and re-reading it per alert was one
            // core call per money movement. An absent adapter now surfaces as
            // an IllegalStateException, which the catch below already handles
            // exactly as the old null check did.
            CustomerName profile = nameResolver.resolve(sender.getCoreExternalId());
            return profile == null ? null
                    : TransactionMessageComposer.partyName(profile.firstName(), profile.lastName());
        } catch (RuntimeException ex) {
            log.warn("Sender name lookup failed for ledger row id={} — naming the account instead: {}",
                    event.transactionId(), ex.toString());
            return null;
        }
    }

    /** Best-effort: a balance we cannot read costs the message its balance line, nothing more. */
    private AccountBalance balanceOf(String accountId) {
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null || accountId == null) {
            return null;
        }
        try {
            return port.getBalance(new AccountRef(accountId));
        } catch (RuntimeException ex) {
            log.warn("Balance read failed for account {} — sending the alert without a balance line: {}",
                    AccountMasking.maskAccount(accountId), ex.toString());
            return null;
        }
    }

    /**
     * The counterparty's customer-facing account NUMBER, for display masking
     * only. Null (falling back to the externalId tail) when the read fails —
     * a display nicety must never cost the alert.
     */
    private String accountNumberOf(String accountId) {
        AccountBalance balance = balanceOf(accountId);
        return balance == null ? null : balance.accountNumber();
    }

    private Counter counter(SettledMovementEvent event, String leg, String outcome) {
        return Counter.builder("innbucks.transaction.notifications")
                .description("Customer transaction alerts, by movement type, leg and delivery outcome")
                .tag("type", event.type().name())
                .tag("leg", leg)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
