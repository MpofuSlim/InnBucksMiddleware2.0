package zw.co.innbucks.middleware.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreBankingException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Maps Fineract failures onto the core-neutral taxonomy. The READ/WRITE split
 * is the whole point:
 *
 * <ul>
 *   <li><b>Reads</b>: 5xx → {@link CoreServerException}; any I/O failure →
 *       {@link CoreTransientException}. Both retryable.</li>
 *   <li><b>Writes</b>: a 5xx RESPONSE means the command reached Fineract and
 *       died there — rollback is likely but unprovable, so →
 *       {@link CoreUnknownOutcomeException} (park, reconcile). HTTP 425 (the
 *       idempotent command is still under processing upstream) is the same
 *       ambiguity. Only failures PROVABLY before the request left (connect
 *       refused / unknown host / connect timeout) map to
 *       {@link CoreTransientException}; a READ timeout mid-write is
 *       unprovable → {@link CoreUnknownOutcomeException}.</li>
 *   <li>401 → {@link CoreAuthException} (our AppUser creds — ops incident).
 *       Fineract uses 403 for BUSINESS rule vetoes (insufficient balance,
 *       wrong account state), so 403 with a domain-error body →
 *       {@link CoreClientException}; a bare 403 → auth.</li>
 *   <li>A savings BLOCK / DEBIT block on the account a write DEBITS →
 *       {@link CoreAccountRestrictedException} (a {@link CoreClientException},
 *       so still FAILED in the ledger). A block on any OTHER account — a
 *       transfer's recipient — stays a plain {@link CoreClientException}
 *       worded without the recipient's account id: see
 *       {@link #accountRestriction}.</li>
 * </ul>
 */
final class FineractErrorMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    /*
     * Fineract's savings-restriction refusals, read from the fork
     * (MpofuSlim/fineract, develop): SavingsAccount.validateForAccountBlock /
     * validateForDebitBlock / validateForCreditBlock throw
     * SavingsAccount{,Debits,Credits}BlockedException(accountId), each an
     * AbstractPlatformDomainRuleException, so PlatformDomainRuleExceptionMapper
     * answers 403 with the code below on errors[0] and the blocked account's
     * numeric id as errors[0].args[0].value. Note the FIRST is "saving."
     * (singular) — copy it, never retype it.
     *
     * Which legs check which (SavingsAccountDomainServiceJpa): a withdrawal
     * checks BLOCK then DEBIT block; a deposit checks BLOCK then CREDIT block;
     * a savings-to-savings transfer runs the withdrawal on its source FIRST,
     * then the deposit on its destination
     * (AccountTransfersWritePlatformServiceImpl). So DEBIT block only ever
     * names the debited account, CREDIT block only ever the credited one, and
     * BLOCK can be either — only args[0] tells a transfer's two sides apart.
     */
    static final String ACCOUNT_BLOCKED = "error.msg.saving.account.blocked.transaction.not.allowed";
    static final String DEBITS_BLOCKED = "error.msg.savings.account.debit.transaction.not.allowed";
    static final String CREDITS_BLOCKED = "error.msg.savings.account.credit.transaction.not.allowed";

    /**
     * The account a write DEBITS — the only account whose restriction is the
     * caller's own. A withdrawal addresses ONE account (by externalId, so its
     * numeric id is not known here): any block refusing it is on the debited
     * account. A transfer names its source by numeric id, which is compared
     * with the id Fineract echoes.
     */
    record DebitedAccount(boolean debits, boolean onlyAccount, Long savingsId) {

        /** Writes that debit no customer account: deposits, onboarding commands. */
        static final DebitedAccount NONE = new DebitedAccount(false, false, null);

        /** A command that touches exactly one account and debits it (withdrawal). */
        static final DebitedAccount ONLY_ACCOUNT = new DebitedAccount(true, true, null);

        /**
         * A transfer's source, by Fineract's numeric savings id. A null id
         * (never expected) only makes a full BLOCK unattributable — it must not
         * throw here, before the call, where an NPE would park the movement
         * UNKNOWN instead of letting Fineract refuse it.
         */
        static DebitedAccount savings(Long savingsId) {
            return new DebitedAccount(true, false, savingsId);
        }
    }

    private FineractErrorMapper() {
    }

    static CoreBankingException mapReadFailure(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String detail = errorDetail(ex);
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract rejected the read credentials: " + detail, ex);
        }
        if (status == 403) {
            return forbidden(detail, ex);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT, "Fineract rejected the request (" + status + "): " + detail, ex);
        }
        return new CoreServerException(CoreProvider.FINERACT, "Fineract read failed (" + status + "): " + detail, ex);
    }

    static CoreBankingException mapWriteFailure(RestClientResponseException ex, TxRef txRef) {
        return mapWriteFailure(ex, txRef, DebitedAccount.NONE);
    }

    static CoreBankingException mapWriteFailure(RestClientResponseException ex, TxRef txRef,
                                                DebitedAccount debited) {
        int status = ex.getStatusCode().value();
        String detail = errorDetail(ex);
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract rejected the write credentials: " + detail, ex);
        }
        if (status == 403) {
            CoreBankingException restriction = accountRestriction(ex, debited);
            return restriction != null ? restriction : forbidden(detail, ex);
        }
        if (status == 425) {
            // Fineract's idempotency layer: the SAME key's original command is
            // still executing. The outcome exists but isn't known yet.
            return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                    "Fineract reports the command is still under processing (425)", ex);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT, "Fineract rejected the command (" + status + "): " + detail, ex);
        }
        // The command reached Fineract and errored — rollback likely but unprovable.
        return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                "Fineract write returned " + status + " after the command was sent: " + detail, ex);
    }

    static CoreBankingException mapReadIoFailure(ResourceAccessException ex) {
        return new CoreTransientException(CoreProvider.FINERACT,
                "Fineract unreachable: " + rootMessage(ex), ex);
    }

    static CoreBankingException mapWriteIoFailure(ResourceAccessException ex, TxRef txRef) {
        if (provablyNeverSent(ex)) {
            return new CoreTransientException(CoreProvider.FINERACT,
                    "Fineract unreachable before the command was sent: " + rootMessage(ex), ex);
        }
        // Read-timeout / connection-reset mid-flight: the command MAY have applied.
        return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                "I/O failure with the command possibly in flight: " + rootMessage(ex), ex);
    }

    /**
     * Connect-phase failures guarantee no request bytes reached Fineract.
     * (java.net names connect timeouts "Connect timed out" vs read timeouts
     * "Read timed out" — the connect check is exact-cause-based first.)
     */
    private static boolean provablyNeverSent(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("connect timed out")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * A savings-restriction refusal, attributed to the account it names; null
     * for every other 403. Matched on the exact globalisation code BEFORE the
     * permission-wording heuristic in {@link #forbidden}.
     *
     * <ul>
     *   <li>On the debited account → {@link CoreAccountRestrictedException}.
     *       The message carries the code (BLOCK vs DEBIT block is ops detail)
     *       but never Fineract's sentence, which names the account id.</li>
     *   <li>On any other account → {@link CoreClientException} — the app's
     *       generic {@code 422 core_rejected} — worded "the receiving account
     *       cannot accept this payment", again without Fineract's sentence:
     *       the sender learns the payment cannot land, not the recipient's
     *       internal account id. Also what a deposit into the customer's own
     *       blocked account becomes (a deposit debits nothing of theirs).</li>
     *   <li>A full BLOCK on a transfer whose echoed id is missing or
     *       unreadable cannot be attributed, and is NOT called the sender's
     *       restriction — the false "your account is paused" is the worse
     *       error. A DEBIT block needs no id: only the debited leg checks it.</li>
     * </ul>
     */
    static CoreBankingException accountRestriction(RestClientResponseException ex, DebitedAccount debited) {
        JsonNode first = firstError(ex);
        if (first == null) {
            return null;
        }
        String code = first.path("userMessageGlobalisationCode").asText("");
        if (!code.equals(ACCOUNT_BLOCKED) && !code.equals(DEBITS_BLOCKED) && !code.equals(CREDITS_BLOCKED)) {
            return null;
        }
        boolean onDebitedAccount = debited.debits() && !code.equals(CREDITS_BLOCKED)
                && (debited.onlyAccount()
                    || code.equals(DEBITS_BLOCKED)
                    || (debited.savingsId() != null && debited.savingsId().equals(blockedAccountId(first))));
        if (onDebitedAccount) {
            return new CoreAccountRestrictedException(CoreProvider.FINERACT,
                    "Fineract refused to debit a restricted account (" + code + ")", ex);
        }
        return new CoreClientException(CoreProvider.FINERACT,
                "Fineract vetoed the request: " + code + ": the receiving account cannot accept this payment", ex);
    }

    /** errors[0].args[0].value — a JSON number from Fineract's Jackson writer; a numeric string is tolerated. */
    private static Long blockedAccountId(JsonNode error) {
        JsonNode value = error.path("args").path(0).path("value");
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual() && value.asText().matches("\\d{1,18}")) {
            return Long.parseLong(value.asText());
        }
        return null;
    }

    private static JsonNode firstError(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode errors = JSON.readTree(body).path("errors");
            return errors.isArray() && !errors.isEmpty() ? errors.get(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static CoreBankingException forbidden(String detail, RestClientResponseException ex) {
        // Fineract's domain-rule vetoes (insufficient balance, invalid account
        // state) come back 403 WITH a structured error body; permission/auth
        // problems carry permission-flavoured codes or no error list at all.
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("permission") || lower.contains("unauthorized") || lower.contains("authorization")) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract authorization failed: " + detail, ex);
        }
        return new CoreClientException(CoreProvider.FINERACT, "Fineract vetoed the request: " + detail, ex);
    }

    /** First error's globalisation code + message from Fineract's error envelope; body preview as fallback. */
    static String errorDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return ex.getStatusText();
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode errors = root.path("errors");
            JsonNode first = errors.isArray() && !errors.isEmpty() ? errors.get(0) : root;
            String code = first.path("userMessageGlobalisationCode").asText("");
            String message = first.path("defaultUserMessage").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return (code.isBlank() ? "" : code + ": ") + message;
            }
        } catch (Exception ignored) {
            // fall through to the raw preview
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
