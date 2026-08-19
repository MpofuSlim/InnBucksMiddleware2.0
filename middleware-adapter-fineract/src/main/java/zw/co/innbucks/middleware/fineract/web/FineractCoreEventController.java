package zw.co.innbucks.middleware.fineract.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.fineract.FineractClient;
import zw.co.innbucks.middleware.fineract.FineractProperties;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos;
import zw.co.innbucks.middleware.corebanking.CoreMovementListener;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Receiver for Fineract's Web hook — the door through which the middleware
 * learns about money that moved WITHOUT its involvement (teller/admin
 * postings, corrections), so those customers get the same SMS as app-initiated
 * movements. Registered against entity {@code SAVINGSACCOUNT}, actions
 * {@code DEPOSIT} / {@code WITHDRAWAL} by {@code provision-cell.sh}.
 *
 * <p><b>Internal-only, and three layers keep it that way</b> (the ticketing
 * fleet's three-files-must-agree doctrine):
 * <ol>
 *   <li>this controller requires the shared token in the PATH — Fineract's
 *       {@code WebHookProcessor} sets no auth header, the URL is the only
 *       secret-bearing channel it offers — compared constant-time;</li>
 *   <li>{@code SecurityConfig} permits the path so the JWT filter doesn't 401
 *       it before the token check runs;</li>
 *   <li>the edge never routes {@code /internal/**} — Fineract reaches this
 *       over the private cell network; the runbook pins an nginx deny.</li>
 * </ol>
 *
 * <p><b>The payload is a trigger, not a source of truth.</b> It carries the
 * original command REQUEST (pre-validation, client-shaped) and a result
 * envelope; amounts in it are unvalidated text. The only fields read are the
 * two numeric ids, and everything customer-facing is then POSITIVELY re-read
 * from Fineract with the read credential — the same re-read idiom as the
 * adapter's saga resumption. A hook body that lies about an amount cannot
 * reach a customer, because the amount never comes from the hook body.
 *
 * <p><b>Answers before it works.</b> The token check and the cheap envelope
 * filter run inline; everything after them — two Fineract re-reads, the
 * ownership listing, the name resolution and the SMS — is handed to a bounded
 * pool and the hook gets its 200 immediately. Inline, that chain parked a
 * Tomcat thread for its whole duration, and a teller posting a batch could hold
 * several at once against the very core that customer traffic is waiting on.
 *
 * <p>The cost of that, stated plainly: an event accepted and then lost to a
 * container kill is never retried, because Fineract's hook dispatch is
 * fire-and-forget with a logging callback and has no retry to drive. The window
 * is small and the pool drains on graceful shutdown; the statement remains the
 * record either way. Inline processing bought a slightly smaller window at the
 * price of a request thread per event, which is the wrong trade under the load
 * this endpoint sees during a teller batch.
 *
 * <p>Never throws: a failure here must never look like a failure of the
 * posting itself.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "innbucks.core.provider", havingValue = "fineract")
public class FineractCoreEventController {

    private static final String ENTITY_SAVINGS = "SAVINGSACCOUNT";

    private final FineractClient client;
    private final FineractProperties properties;
    private final CoreMovementListener movementListener;
    private final Clock clock;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final String expectedToken;

    public FineractCoreEventController(FineractClient client,
                                       FineractProperties properties,
                                       CoreMovementListener movementListener,
                                       Clock clock,
                                       @Qualifier("coreEventExecutor") Executor executor,
                                       MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.movementListener = movementListener;
        this.clock = clock;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.expectedToken = properties.coreEventsToken() == null
                ? "" : properties.coreEventsToken().trim();
        if (this.expectedToken.isEmpty()) {
            log.warn("FINERACT_CORE_EVENTS_TOKEN is not set — the core-event webhook is DISABLED. "
                    + "Movements posted directly in Fineract (teller/admin) will not SMS the customer. "
                    + "Set the token and run provision-cell.sh to register the hook.");
        }
    }

    /**
     * Both spellings on purpose. Fineract's Web hook hands the Payload URL to
     * Retrofit, which REQUIRES a trailing slash ({@code baseUrl must end in /}
     * — hook registration 500s without one) and then posts to that slashed
     * URL. Spring stopped matching trailing-slash variants implicitly, so
     * without the second mapping every hook fire would 404 — silently, since
     * Fineract's callback just logs. The slash-less form stays for curl-driven
     * smoke tests.
     */
    @PostMapping({"/internal/core-events/fineract/{token}", "/internal/core-events/fineract/{token}/"})
    public ResponseEntity<Void> onEvent(@PathVariable("token") String token,
                                        @RequestHeader(value = "X-Fineract-Entity", required = false) String entity,
                                        @RequestHeader(value = "X-Fineract-Action", required = false) String action,
                                        @RequestBody(required = false) HookEnvelope body) {
        // Disabled or wrong token: identical 404 either way — an endpoint that
        // answers 401 confirms it exists, and this one is not meant to exist
        // for anyone without the token.
        if (expectedToken.isEmpty() || !constantTimeEquals(expectedToken, token)) {
            return ResponseEntity.notFound().build();
        }

        // Inline, because it is pure CPU over already-parsed JSON and because
        // queueing work we would only discard would let irrelevant events
        // crowd out real ones.
        if (!ENTITY_SAVINGS.equalsIgnoreCase(entity) || body == null || body.response() == null) {
            count("ignored");
            return ResponseEntity.ok().build();
        }
        String verb = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (!verb.equals("DEPOSIT") && !verb.equals("WITHDRAWAL")) {
            count("ignored");
            return ResponseEntity.ok().build();
        }
        Long savingsId = body.response().savingsId();
        Long transactionId = body.response().resourceId();
        if (savingsId == null || transactionId == null) {
            log.debug("Core event without savingsId/resourceId — ignored");
            count("ignored");
            return ResponseEntity.ok().build();
        }

        // Everything past here talks to Fineract and then to the SMS gateway.
        // Off the request thread; the hook is answered below either way. A
        // saturated queue is handled by the executor's rejection policy (drop,
        // count, shout) — execute() does not throw for it.
        count("accepted");
        executor.execute(() -> {
            try {
                handle(savingsId, transactionId);
            } catch (RuntimeException ex) {
                // Never escapes the pool thread: at this point the hook has
                // already been answered, and there is nobody left to tell.
                count("failed");
                log.warn("Core-event handling failed (entity={}, action={}, savingsId={}): {}",
                        entity, action, savingsId, ex.toString());
            }
        });
        return ResponseEntity.ok().build();
    }

    private void count(String outcome) {
        meterRegistry.counter("innbucks.core.events", "outcome", outcome).increment();
    }

    private void handle(Long savingsId, Long transactionId) {
        // Positive re-read: the hook told us WHERE to look, Fineract's API
        // says WHAT happened. Both reads ride the read-only credential.
        FineractDtos.SavingsAccountResponse account = client.findSavingsById(savingsId);
        if (account == null || account.externalId() == null || account.externalId().isBlank()) {
            log.debug("Core event for savings {} — account unknown or carries no externalId; ignored", savingsId);
            return;
        }
        FineractDtos.SavingsTransaction txn = client.findSavingsTransactionById(savingsId, transactionId);
        if (txn == null || Boolean.TRUE.equals(txn.reversed())) {
            return;
        }

        BigDecimal amount = txn.amount();
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        TransactionDirection direction = "CREDIT".equalsIgnoreCase(txn.entryType())
                ? TransactionDirection.CREDIT : TransactionDirection.DEBIT;

        movementListener.onCoreMovement(new CoreMovementObserved(
                account.externalId(),
                direction,
                MinorUnits.ofMajor(amount, properties.currency()),
                // No narrative: Fineract's transactionType().value() is just
                // "Deposit"/"Withdrawal" — a customer already read that verb
                // in the sentence, and the first live alert rendered it as a
                // useless "Narration - Deposit." line.
                null,
                txn.externalId() == null || txn.externalId().isBlank() ? null : txn.externalId(),
                String.valueOf(txn.id()),
                // Receipt time, NOT the transaction's value date: Fineract
                // stores savings transactions as DATES, and rendering the
                // date's midnight put "at 02.00" on a 14.20 deposit (00.00
                // UTC in the cell's zone). The hook fires within seconds of
                // the posting, so now() is the honest customer-facing time.
                clock.instant()));
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The trimmed hook envelope — ONLY the two ids the re-read needs. The
     * request map, actor fields and everything else deliberately fall through
     * ignored: nothing customer-facing may come from this body.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HookEnvelope(HookResponse response, Map<String, Object> request) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HookResponse(Long savingsId, Long resourceId) {
    }
}
