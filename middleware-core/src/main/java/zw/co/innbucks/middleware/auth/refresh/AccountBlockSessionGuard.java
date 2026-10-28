package zw.co.innbucks.middleware.auth.refresh;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.exception.AccountRestrictedSessionException;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.customer.Customer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ends a customer's sessions when the bank FULLY blocks their account in the
 * core. Staff restrict a customer by blocking the wallet in the back office;
 * the core then refuses every payment at once, and this guard makes the next
 * session refresh (about every access-token lifetime, ten minutes by default)
 * end every session the customer holds. The middleware grows no staff surface
 * of its own: it reads what the core already reports, on the same account
 * listing payments read.
 *
 * <p><b>Policy.</b>
 * <ul>
 *   <li>A FULL block on any account the app can see refuses the refresh,
 *       revokes every refresh family ({@link RefreshTokenService#revokeAllSessions}),
 *       audits it, and answers 403 {@code account_restricted}. Today that set
 *       is the wallet alone; if a second app-visible account ships, decide
 *       whether a full block on it alone should end the session.</li>
 *   <li>A DEBIT-only or CREDIT-only block does NOT end sessions. A debit block
 *       is the bank's "pause payments" lever — the core already refuses every
 *       payment out — and the customer may still need to see their balance and
 *       statements, or receive money. Signing them out would turn a payment
 *       pause into a lockout. (Fineract stores one sub-status, so blocking
 *       BOTH directions separately becomes a full block and does sign out.)</li>
 *   <li>A customer with no core mapping has no account to read: skipped.</li>
 *   <li><b>Fails OPEN.</b> Any failure to read — Fineract down, breaker open,
 *       our credential refused, a malformed listing — lets the refresh
 *       through and is counted ({@code outcome=read_failed}). The money is
 *       already blocked in the core, so failing open costs at most one more
 *       access-token lifetime of reads; failing closed would turn a Fineract
 *       blip into every active customer being signed out.</li>
 * </ul>
 *
 * <p><b>Transactions.</b> Called after {@link RefreshTokenService#rotate} has
 * committed, with no transaction and no pooled connection held — the core read
 * is an HTTP call of ~100 ms, or a timeout. The revocation then runs in its own
 * short transaction. See {@code LoginController.refresh} for why rotation comes
 * first.
 *
 * <p>Metric: {@code innbucks.auth.refresh.block_check{outcome}} with outcomes
 * {@code none}, {@code debits_blocked}, {@code credits_blocked},
 * {@code fully_blocked}, {@code unmapped}, {@code read_failed}; all registered
 * at zero. A sustained {@code read_failed} rate means blocks are not ending
 * sessions — page it like a core outage.
 */
@Slf4j
@Component
public class AccountBlockSessionGuard {

    /** {@code refresh_token.revocation_reason} for families ended by a full block. */
    static final String REVOCATION_REASON = "account_blocked";

    enum Outcome {
        NONE("none"),
        DEBITS_BLOCKED("debits_blocked"),
        CREDITS_BLOCKED("credits_blocked"),
        FULLY_BLOCKED("fully_blocked"),
        UNMAPPED("unmapped"),
        READ_FAILED("read_failed");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final ObjectProvider<CoreBankingPort> corePort;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final TransactionTemplate revokeTransaction;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public AccountBlockSessionGuard(ObjectProvider<CoreBankingPort> corePort,
                                    RefreshTokenService refreshTokenService,
                                    AuditService auditService,
                                    PlatformTransactionManager transactionManager,
                                    MeterRegistry meterRegistry) {
        this.corePort = corePort;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.revokeTransaction = new TransactionTemplate(transactionManager);
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("innbucks.auth.refresh.block_check")
                    .description("Session-refresh reads of the customer's account block in the core, by outcome")
                    .tag("outcome", outcome.tag)
                    .register(meterRegistry));
        }
    }

    /**
     * Lets the refresh continue, or — on a full block — ends every session the
     * customer holds and throws {@link AccountRestrictedSessionException}.
     * Never throws for a failed read (fails open, counted).
     */
    public void requireSessionAllowed(Customer customer, String deviceHash) {
        if (customer.getCoreExternalId() == null) {
            count(Outcome.UNMAPPED);
            return;
        }
        Outcome outcome = readOutcome(customer);
        count(outcome);
        if (outcome != Outcome.FULLY_BLOCKED) {
            return;
        }
        // Own short transaction: revokeAllSessions is MANDATORY (it must commit
        // with whatever motivates it), and here nothing else does. It takes the
        // customer row FOR NO KEY UPDATE, which a concurrent rotation on another
        // device waits for, or is seen by — the same guarantee a PIN reset has.
        Integer revoked = revokeTransaction.execute(status ->
                refreshTokenService.revokeAllSessions(customer.getId(), REVOCATION_REASON));
        auditService.record(AuditAction.REFRESH_BLOCKED_ACCOUNT_RESTRICTED, AuditOutcome.FAILURE,
                customer.getId(), deviceHash, Map.of("refreshTokensRevoked", revoked == null ? 0 : revoked));
        log.info("Refresh refused for customer={}: the core reports the account fully blocked; "
                + "{} refresh token row(s) revoked", customer.getId(), revoked);
        throw new AccountRestrictedSessionException();
    }

    private Outcome readOutcome(Customer customer) {
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null) {
            log.warn("No core banking port in this context; refresh block check skipped (failing open)");
            return Outcome.READ_FAILED;
        }
        try {
            return assess(port.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId())));
        } catch (RuntimeException ex) {
            // Exception type and message only: the message is ours or the
            // adapter's mapped detail, never a customer secret. No stack —
            // a core outage would print one per refresh.
            log.warn("Refresh block check could not read the core for customer={} ({}: {}); failing open",
                    customer.getId(), ex.getClass().getSimpleName(), ex.getMessage());
            return Outcome.READ_FAILED;
        }
    }

    /**
     * The decision, kept pure: the most severe restriction across the
     * customer's app-visible accounts. Only {@link Outcome#FULLY_BLOCKED}
     * ends sessions; the other outcomes exist for the metric.
     */
    static Outcome assess(List<DepositAccountRef> accounts) {
        boolean debits = false;
        boolean credits = false;
        for (DepositAccountRef account : accounts) {
            AccountRestriction restriction = account.restriction();
            if (restriction == AccountRestriction.FULLY_BLOCKED) {
                return Outcome.FULLY_BLOCKED;
            }
            debits |= restriction == AccountRestriction.DEBITS_BLOCKED;
            credits |= restriction == AccountRestriction.CREDITS_BLOCKED;
        }
        if (debits) {
            return Outcome.DEBITS_BLOCKED;
        }
        return credits ? Outcome.CREDITS_BLOCKED : Outcome.NONE;
    }

    private void count(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
