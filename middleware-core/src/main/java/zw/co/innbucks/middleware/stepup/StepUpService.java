package zw.co.innbucks.middleware.stepup;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.otp.ConsumedVerificationTokenStore;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.VerificationTokenInvalidException;
import zw.co.innbucks.middleware.otp.VerificationTokenVerifier;
import zw.co.innbucks.middleware.otp.VerifiedToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Enforces SMS step-up on high-value withdrawals and transfers. Called by
 * {@code MovementService} BEFORE the idempotency claim, so a refused movement
 * leaves no state anywhere.
 *
 * <p>The approval token is the OTP slice's verification token with purpose
 * {@code STEP_UP} and a {@code txn_fp} claim; it is accepted only when ALL of
 * these hold — signature/expiry/audience/issuer/purpose (verifier), the
 * fingerprint matches THIS movement (constant-time), and the token has never
 * been used before (single-use via {@link ConsumedVerificationTokenStore},
 * shared with PIN set/reset). The fingerprint embeds the customer id, so a
 * token can never approve another customer's movement — or a different
 * amount/destination of this customer's.
 *
 * <p>Crash-retry caveat (documented, accepted): a retry of an ALREADY-EXECUTED
 * movement with the same Idempotency-Key replays the recorded outcome, but
 * arrives here first — and its token was consumed by the first execution. The
 * app re-approves (one extra OTP) and the retry then resolves from the ledger
 * without re-executing. Rare path; the alternative (not consuming) would let a
 * captured token re-fire the same transfer with fresh keys for its whole TTL.
 */
@Slf4j
@Service
@EnableConfigurationProperties(StepUpProperties.class)
public class StepUpService {

    private final StepUpProperties properties;
    private final VerificationTokenVerifier verifier;
    private final ConsumedVerificationTokenStore consumedStore;
    private final AuditService auditService;
    private final Counter required;
    private final Counter accepted;
    private final Counter rejected;

    public StepUpService(StepUpProperties properties,
                         VerificationTokenVerifier verifier,
                         ConsumedVerificationTokenStore consumedStore,
                         AuditService auditService,
                         MeterRegistry meterRegistry) {
        this.properties = properties;
        this.verifier = verifier;
        this.consumedStore = consumedStore;
        this.auditService = auditService;
        this.required = meterRegistry.counter("innbucks.stepup.challenges", "outcome", "required");
        this.accepted = meterRegistry.counter("innbucks.stepup.challenges", "outcome", "accepted");
        this.rejected = meterRegistry.counter("innbucks.stepup.challenges", "outcome", "rejected");
    }

    /** The fingerprint a STEP_UP token must carry to approve this movement. */
    public String fingerprint(UUID customerId, LedgerTransactionType type,
                              String sourceAccountId, String targetAccountId,
                              long amountMinor, String currency) {
        return TxnFingerprint.of(customerId, type, sourceAccountId, targetAccountId,
                amountMinor, currency);
    }

    /**
     * The half of the check that needs NOTHING from the core: whether this
     * movement needs an approval at all, and whether one was supplied.
     *
     * <p>Callers run this FIRST, ahead of the ownership check, because the
     * answer is computed entirely from configuration, the caller's KYC tier and
     * the request body. The app's designed flow hits it on the first attempt of
     * every high-value movement — send without a token, get the fingerprint
     * back, do the OTP, retry with the token — so leaving it until after
     * ownership meant a core round trip on a request that was always going to
     * be refused.
     *
     * <p><b>Presence only.</b> A token that is present but forged, expired,
     * replayed or bound to a different transaction is NOT rejected here — that
     * is {@link #enforce}'s job, and it still runs after the ownership check,
     * so nothing is ever consumed for an account the caller does not own.
     *
     * <p>Writes nothing: no audit row, no consumed-token row. A refusal here
     * leaves exactly as little state as a refusal in {@code enforce}.
     *
     * @throws StepUpRequiredException no token supplied (403 step_up_required + the fp)
     */
    public void requireApprovalToken(UUID customerId, KycTier tier, LedgerTransactionType type,
                                     String sourceAccountId, String targetAccountId,
                                     long amountMinor, String currency, String stepUpToken) {
        if (!stepUpApplies(tier, amountMinor)) {
            return;
        }
        if (stepUpToken != null && !stepUpToken.isBlank()) {
            return;
        }
        required.increment();
        log.info("Step-up required customer={} type={} amountMinor={} tier={}",
                customerId, type, amountMinor, tier);
        throw new StepUpRequiredException(fingerprint(customerId, type, sourceAccountId,
                targetAccountId, amountMinor, currency));
    }

    /**
     * Refuses the movement unless it is below the tier threshold or carries a
     * valid, unconsumed approval for exactly this fingerprint. On success the
     * token is consumed (single-use) before any money-touching state exists.
     *
     * <p>This remains the AUTHORITATIVE check and is correct on its own: it
     * repeats {@link #requireApprovalToken} rather than trusting that a caller
     * ran it. Callers that did run it simply find the absent-token branch
     * already taken.
     *
     * @throws StepUpRequiredException           no token supplied (403 step_up_required + the fp)
     * @throws VerificationTokenInvalidException bad/expired/mismatched/replayed token (401)
     */
    public void enforce(UUID customerId, KycTier tier, LedgerTransactionType type,
                        String sourceAccountId, String targetAccountId,
                        long amountMinor, String currency, String stepUpToken) {
        if (!stepUpApplies(tier, amountMinor)) {
            return;
        }
        requireApprovalToken(customerId, tier, type, sourceAccountId, targetAccountId,
                amountMinor, currency, stepUpToken);
        String expectedFp = fingerprint(customerId, type, sourceAccountId, targetAccountId,
                amountMinor, currency);
        try {
            VerifiedToken token = verifier.verify(stepUpToken, OtpPurpose.STEP_UP);
            if (token.txnFp() == null || !constantTimeEquals(token.txnFp(), expectedFp)) {
                throw new VerificationTokenInvalidException(
                        VerificationTokenInvalidException.Reason.TXN_MISMATCH,
                        "token approves a different transaction");
            }
            // Single-use: first consume wins; a second presentation of the same
            // jti throws REPLAYED. Committed in the surrounding transaction of
            // the movement path — before any core call is attempted.
            consumedStore.consume(token.jti(), token.expiresAt());
        } catch (VerificationTokenInvalidException ex) {
            rejected.increment();
            auditService.record(AuditAction.STEP_UP_REJECTED, AuditOutcome.FAILURE, customerId, null);
            throw ex;
        }
        accepted.increment();
        auditService.record(AuditAction.STEP_UP_APPROVED, AuditOutcome.SUCCESS, customerId, null);
    }

    /**
     * Whether this movement is in scope for step-up at all. The ONE place the
     * master switch and the tier threshold are read, so the pre-check and the
     * authoritative check can never disagree about which movements need an
     * approval.
     */
    private boolean stepUpApplies(KycTier tier, long amountMinor) {
        return properties.enabled() && amountMinor >= properties.thresholdFor(tier);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
