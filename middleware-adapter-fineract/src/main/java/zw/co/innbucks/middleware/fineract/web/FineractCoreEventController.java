package zw.co.innbucks.middleware.fineract.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <p>Always answers 2xx/4xx quickly and never throws: Fineract's hook
 * dispatch is fire-and-forget with a logging callback, and a failure here
 * must never look like a failure of the posting itself.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "innbucks.core.provider", havingValue = "fineract")
public class FineractCoreEventController {

    private static final String ENTITY_SAVINGS = "SAVINGSACCOUNT";

    private final FineractClient client;
    private final FineractProperties properties;
    private final CoreMovementListener movementListener;
    private final String expectedToken;

    public FineractCoreEventController(FineractClient client,
                                       FineractProperties properties,
                                       CoreMovementListener movementListener) {
        this.client = client;
        this.properties = properties;
        this.movementListener = movementListener;
        this.expectedToken = properties.coreEventsToken() == null
                ? "" : properties.coreEventsToken().trim();
        if (this.expectedToken.isEmpty()) {
            log.warn("FINERACT_CORE_EVENTS_TOKEN is not set — the core-event webhook is DISABLED. "
                    + "Movements posted directly in Fineract (teller/admin) will not SMS the customer. "
                    + "Set the token and run provision-cell.sh to register the hook.");
        }
    }

    @PostMapping("/internal/core-events/fineract/{token}")
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
        try {
            handle(entity, action, body);
        } catch (RuntimeException ex) {
            // Never bubble: a broken alert must not read as a broken posting.
            // 200 regardless — Fineract's hook has no retry semantics worth
            // driving, and the drop is already counted in the alert metrics.
            log.warn("Core-event handling failed (entity={}, action={}): {}", entity, action, ex.toString());
        }
        return ResponseEntity.ok().build();
    }

    private void handle(String entity, String action, HookEnvelope body) {
        if (!ENTITY_SAVINGS.equalsIgnoreCase(entity) || body == null || body.response() == null) {
            return;
        }
        String verb = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (!verb.equals("DEPOSIT") && !verb.equals("WITHDRAWAL")) {
            return;
        }
        Long savingsId = body.response().savingsId();
        Long transactionId = body.response().resourceId();
        if (savingsId == null || transactionId == null) {
            log.debug("Core event without savingsId/resourceId — ignored");
            return;
        }

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
                narrative(txn),
                txn.externalId() == null || txn.externalId().isBlank() ? null : txn.externalId(),
                String.valueOf(txn.id()),
                occurredAt(txn.date())));
    }

    private static String narrative(FineractDtos.SavingsTransaction txn) {
        return txn.transactionType() == null ? null : txn.transactionType().value();
    }

    /** The transaction's value date at UTC start-of-day; now() if the shape is unexpected — the SMS renders it in the cell zone. */
    private static Instant occurredAt(Object rawDate) {
        try {
            if (rawDate instanceof List<?> parts && parts.size() >= 3) {
                return LocalDate.of(intOf(parts.get(0)), intOf(parts.get(1)), intOf(parts.get(2)))
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            if (rawDate instanceof CharSequence text) {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return Instant.now();
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
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
