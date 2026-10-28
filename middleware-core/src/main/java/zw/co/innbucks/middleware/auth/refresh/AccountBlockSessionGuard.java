package zw.co.innbucks.middleware.auth.refresh;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   <li><b>Fails OPEN, on errors AND on latency.</b> Any failure to read —
 *       Fineract down, breaker open, our credential refused, a malformed
 *       listing — lets the refresh through ({@code outcome=read_failed}); so
 *       does a read slower than {@code innbucks.auth.refresh-block-check.timeout}
 *       ({@code timed_out}) and a refresh that finds every read slot already
 *       busy ({@code busy}). The money is already blocked in the core, so
 *       failing open costs at most one more access-token lifetime of reads;
 *       failing closed — or simply WAITING — would turn a Fineract blip into
 *       customers being signed out.</li>
 * </ul>
 *
 * <p><b>Why latency is bounded, not just errors.</b> A Fineract read that
 * hangs is retried by the adapter (three attempts over the 10 s read timeout:
 * about 30 s), and the breaker only opens after enough calls have FINISHED.
 * Waited for on the request thread, every refresh in that window would hold a
 * Tomcat worker for half a minute — a Fineract stall would then starve
 * {@code /auth/login} and every other endpoint, where before refresh never
 * touched the core at all. So the read runs off the request thread, at most
 * {@code max-concurrent-reads} at once and never queued, and the refresh waits
 * at most the timeout:
 * <ul>
 *   <li>A read past the timeout is ABANDONED, not interrupted. It finishes on
 *       its own inside the adapter's timeouts and its answer is discarded.
 *       Interrupting it would make the adapter count a merely-slow call as a
 *       failure on the circuit breaker that payments share, so a slow-ish
 *       Fineract could open the breaker on the money path because of refreshes.</li>
 *   <li>With every slot busy (a stall), a refresh does not queue: it is let
 *       through at once. So the extra load this check can put on a struggling
 *       core is bounded by the slots, not by the refresh rate.</li>
 *   <li>A slot is a {@link Semaphore} permit, taken before the read is handed
 *       to a thread and given back by the read itself as its last act — so it
 *       is free again BEFORE the waiting refresh wakes, and {@code busy} means
 *       exactly that every slot holds a read that has not finished. The
 *       threads come from a pool that never refuses a task (no queue, no
 *       thread cap); the permits keep it near one thread per slot (a thread
 *       still winding down from one read can briefly overlap the next). It
 *       used to be a fixed pool of {@code max-concurrent-reads} threads over a
 *       {@code SynchronousQueue}, whose "full" also counted a thread that had
 *       just FINISHED a read but was not yet waiting for the next one: a
 *       refresh arriving in that gap was refused as {@code busy} with every
 *       slot free and skipped the block check — a few percent of back-to-back
 *       refreshes on one slot.</li>
 * </ul>
 *
 * <p><b>Ordering and transactions.</b> {@code LoginController.refresh} calls
 * this AFTER {@link RefreshTokenService#liveSessionCustomer} has shown the
 * token live and BEFORE {@link RefreshTokenService#rotate} spends it, with no
 * transaction and no pooled connection held. What that order buys: on a full
 * block nothing is rotated at all (the revocation runs in its own short
 * transaction), and a refresh that fails in this guard's own path leaves the
 * presented token unspent. What it does NOT buy: a client that gives up
 * waiting is not saved by it. The refresh carries on without the client and
 * {@code rotate()} spends the token anyway, so the successor is lost with the
 * response and the client's retry with the old token is a replay that revokes
 * the family — the same as if the core were read after rotation, and the same
 * as any lost refresh response. The only protection against a slow core
 * signing customers out is the timeout, which must stay well under the app's
 * HTTP timeout; never raise it on the strength of this ordering.
 *
 * <p>Metric: {@code innbucks.auth.refresh.block_check{outcome}} with outcomes
 * {@code none}, {@code debits_blocked}, {@code credits_blocked},
 * {@code fully_blocked}, {@code unmapped}, {@code read_failed},
 * {@code timed_out}, {@code busy}; all registered at zero. A sustained rate of
 * any of the last three means blocks are not ending sessions — page it like a
 * core outage.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RefreshBlockCheckProperties.class)
public class AccountBlockSessionGuard implements DisposableBean {

    /** {@code refresh_token.revocation_reason} for families ended by a full block. */
    static final String REVOCATION_REASON = "account_blocked";

    enum Outcome {
        NONE("none"),
        DEBITS_BLOCKED("debits_blocked"),
        CREDITS_BLOCKED("credits_blocked"),
        FULLY_BLOCKED("fully_blocked"),
        UNMAPPED("unmapped"),
        READ_FAILED("read_failed"),
        TIMED_OUT("timed_out"),
        BUSY("busy");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final ObjectProvider<CoreBankingPort> corePort;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final TransactionTemplate revokeTransaction;
    private final Duration readTimeout;
    /** One permit per read allowed in flight; see the class javadoc. */
    private final Semaphore readSlots;
    private final ThreadPoolExecutor readThreads;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public AccountBlockSessionGuard(ObjectProvider<CoreBankingPort> corePort,
                                    RefreshTokenService refreshTokenService,
                                    AuditService auditService,
                                    PlatformTransactionManager transactionManager,
                                    MeterRegistry meterRegistry,
                                    RefreshBlockCheckProperties properties) {
        this.corePort = corePort;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.revokeTransaction = new TransactionTemplate(transactionManager);
        this.readTimeout = properties.timeout();
        int maxConcurrentReads = properties.maxConcurrentReads();
        this.readSlots = new Semaphore(maxConcurrentReads);
        // The permits are the bound; this pool only supplies threads, and
        // never refuses a task it is handed (SynchronousQueue hands the read
        // to an idle thread or starts one), so a permitted read always starts
        // at once and nothing ever waits behind a hung read. Idle threads die
        // after 30 s. Daemon threads, so an abandoned read can never hold up
        // a shutdown.
        this.readThreads = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                30, TimeUnit.SECONDS, new SynchronousQueue<>(), daemonThreads());
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("innbucks.auth.refresh.block_check")
                    .description("Session-refresh reads of the customer's account block in the core, by outcome")
                    .tag("outcome", outcome.tag)
                    .register(meterRegistry));
        }
        log.info("Refresh block check: core read bounded to {} ms, at most {} in flight",
                this.readTimeout.toMillis(), maxConcurrentReads);
    }

    /**
     * Lets the refresh continue, or — on a full block — ends every session the
     * customer holds and throws {@link AccountRestrictedSessionException}.
     * Never throws for a failed or slow read (fails open, counted), and never
     * waits longer than the configured timeout for the core.
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
        CoreCustomerRef ref = new CoreCustomerRef(customer.getCoreExternalId());
        if (!readSlots.tryAcquire()) {
            // Every slot is held by a read that has not come back: the core is
            // stalling. DEBUG, not WARN — this fires on every refresh for the
            // length of the stall; the busy counter is the signal.
            log.debug("Refresh block check skipped for customer={}: every read slot is busy (failing open)",
                    customer.getId());
            return Outcome.BUSY;
        }
        Future<List<DepositAccountRef>> pending;
        try {
            pending = readThreads.submit(holdingSlot(withCallerMdc(() -> port.listDepositAccountRefs(ref))));
        } catch (RejectedExecutionException ex) {
            // Only once the pool is shut down (the context is closing).
            readSlots.release();
            return Outcome.READ_FAILED;
        }
        try {
            return assess(pending.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            // Abandon, never interrupt (see the class javadoc: the breaker the
            // payment path shares would count the interruption as a failure),
            // and never cancel: the read keeps its slot until it finishes, and
            // a read cancelled before it started would never give its slot back.
            log.warn("Refresh block check for customer={} gave up after {} ms waiting for the core; failing open",
                    customer.getId(), readTimeout.toMillis());
            return Outcome.TIMED_OUT;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            // Exception type and message only: the message is ours or the
            // adapter's mapped detail, never a customer secret. No stack —
            // a core outage would print one per refresh.
            log.warn("Refresh block check could not read the core for customer={} ({}: {}); failing open",
                    customer.getId(), cause.getClass().getSimpleName(), cause.getMessage());
            return Outcome.READ_FAILED;
        } catch (InterruptedException ex) {
            // The read is abandoned exactly as on a timeout.
            Thread.currentThread().interrupt();
            return Outcome.READ_FAILED;
        } catch (RuntimeException ex) {
            // A bug in assess() or a malformed answer: still never a sign-out.
            log.warn("Refresh block check could not assess the core's answer for customer={} ({}: {}); "
                    + "failing open", customer.getId(), ex.getClass().getSimpleName(), ex.getMessage());
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

    /** Stops the read threads; an abandoned read still in flight is not waited for. */
    @Override
    public void destroy() {
        readThreads.shutdownNow();
    }

    /** Free read slots right now; for tests. */
    int freeReadSlots() {
        return readSlots.availablePermits();
    }

    private void count(Outcome outcome) {
        counters.get(outcome).increment();
    }

    /**
     * Gives the read's slot back as the read's own last act — inside the
     * task, so BEFORE its future completes and the waiting refresh wakes.
     * Released any later (after the future completes) and a refresh made right
     * after the previous one finished could still find the slot taken.
     */
    private <T> Callable<T> holdingSlot(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } finally {
                readSlots.release();
            }
        };
    }

    /**
     * Carries the caller's MDC (correlation id, country) onto the read thread,
     * so the adapter's outbound correlation header and every log line of the
     * read tie back to the refresh that asked.
     */
    private static <T> Callable<T> withCallerMdc(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "refresh-block-check-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
