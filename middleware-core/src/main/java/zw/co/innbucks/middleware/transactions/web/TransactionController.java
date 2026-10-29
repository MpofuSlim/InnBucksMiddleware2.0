package zw.co.innbucks.middleware.transactions.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.limits.LimitExceededProblem;
import zw.co.innbucks.middleware.limits.PaymentLimitExceededException;
import zw.co.innbucks.middleware.stepup.StepUpRequiredException;
import zw.co.innbucks.middleware.transactions.MovementRequests.DepositRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;
import zw.co.innbucks.middleware.transactions.MovementService;
import zw.co.innbucks.middleware.transactions.TransactionResponse;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
@Tag(name = "transactions", description = "Money movement for the authenticated customer. Every "
        + "endpoint requires a caller-supplied Idempotency-Key (namespaced per customer server-side); "
        + "retries with the same key return the original movement's recorded outcome — a FRESH attempt "
        + "needs a fresh key. PROCESSING responses resolve in the background; poll /me/accounts.")
public class TransactionController {

    private static final String COMMON_RESPONSES = """
            {"transactionId":"7b1e2d3c-4f5a-4b6c-8d9e-0a1b2c3d4e5f",
             "status":"SUCCESS","coreTxRef":"501","failureCode":null,"failureMessage":null}
            """;

    private static final String STEP_UP_REQUIRED = """
            {"type":"about:blank","title":"Approval required","status":403,
             "detail":"This amount needs SMS approval. Request a code, verify it, and retry with the X-Step-Up-Token header.",
             "errorCode":"step_up_required",
             "txnFp":"9f2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e"}
            """;

    /** CoreBankingExceptionHandler.restricted — fixed copy, never the core's reason. */
    private static final String ACCOUNT_RESTRICTED = """
            {"type":"about:blank","title":"Account restricted","status":403,
             "detail":"Payments from this account are paused. Please contact support.",
             "errorCode":"account_restricted"}
            """;

    /**
     * {@code CoreBankingExceptionHandler.upstreamAuth}, verbatim: the core refused
     * this service's own credentials or a permission they need. Our fault, never
     * the request's, so the wording is generic and the detail stays in the logs.
     */
    private static final String CORE_REFUSED_US = """
            {"type":"about:blank","title":"Service temporarily unavailable","status":502,
             "detail":"We couldn't reach the banking system. Please try again shortly.",
             "errorCode":"core_banking_unavailable"}
            """;
    private static final String CORE_REFUSED_US_DESCRIPTION = "The core banking system refused this "
            + "service's own credentials or a permission they need (errorCode core_banking_unavailable): "
            + "an operations fault, never the request's, and nothing moved. If the core refused the "
            + "movement itself, its ledger row is FAILED (failureCode core_auth_failed) and a retry with "
            + "the SAME Idempotency-Key is answered 200 FAILED from that row, so a fresh attempt needs a "
            + "fresh key; if it refused an earlier read, nothing was recorded";

    /**
     * The customer of the GET /me/limits example (basic, 112,500 of 150,000
     * sent today, 37,500 sendable now) paying 50,000. remainingMinor is the
     * screen's sendableNowMinor.
     */
    private static final String LIMIT_DAILY_EXAMPLE = """
            {"type":"about:blank","title":"Payment limit reached","status":422,
             "detail":"This payment would take you over your daily sending limit.",
             "errorCode":"limit_exceeded","limit":"daily","limitMinor":150000,"remainingMinor":37500,
             "resetsAt":"2026-10-29T00:00:00+02:00","kycTier":"basic","nextTier":"standard"}
            """;

    /**
     * The same customer paying 120,000. remainingMinor is what passes NOW
     * (37,500 — the day has less left than the 100,000 cap), not the cap.
     */
    private static final String LIMIT_PER_PAYMENT_EXAMPLE = """
            {"type":"about:blank","title":"Payment limit reached","status":422,
             "detail":"This amount is more than you can send in one payment.",
             "errorCode":"limit_exceeded","limit":"per_payment","limitMinor":100000,"remainingMinor":37500,
             "resetsAt":null,"kycTier":"basic","nextTier":"standard"}
            """;

    private static final String LIMIT_COUNT_EXAMPLE = """
            {"type":"about:blank","title":"Payment limit reached","status":422,
             "detail":"You have made as many payments as you can today.",
             "errorCode":"limit_exceeded","limit":"daily_count","limitCount":10,"remainingCount":0,
             "resetsAt":"2026-10-29T00:00:00+02:00","kycTier":"enhanced","nextTier":null}
            """;

    private static final String CORE_REJECTED_EXAMPLE = """
            {"type":"about:blank","title":"Request refused","status":422,
             "detail":"Insufficient account balance.","errorCode":"core_rejected"}
            """;

    private static final String RATE_LIMITED_EXAMPLE = """
            {"type":"about:blank","title":"Too many requests","status":429,
             "detail":"You've made too many requests in a short time. Please wait a moment and try again.",
             "errorCode":"rate_limited","retryAfterSeconds":12}
            """;

    private static final String LIMITS_422_DESCRIPTION = "Refused before anything was written. "
            + "errorCode limit_exceeded: the payment would break the caller's per-tier limit (fields "
            + "limit, limitMinor/limitCount, remainingMinor/remainingCount, resetsAt, kycTier, nextTier; "
            + "remainingMinor is the most that passes NOW, the same figure as GET /me/limits "
            + "sendableNowMinor — for per_payment it can be below limitMinor; "
            + "checked BEFORE step-up, so a payment that will be refused never gets step_up_required). "
            + "Also: key reused with a different body (idempotency_conflict), or the core rejected the "
            + "movement (core_rejected).";

    /**
     * The limits half is the withdrawal's; the core half differs, because a
     * transfer touches someone else's account and its refusals are fixed copy
     * at the adapter (FineractErrorMapper.transferRefusal) — never the core's
     * wording, so the withdrawal's core_rejected example ("Insufficient
     * account balance.", the core's own words about the caller's own account)
     * does not apply here.
     */
    private static final String TRANSFER_422_DESCRIPTION = "Refused before anything was written, or "
            + "refused by the core. errorCode limit_exceeded: the payment would break the caller's per-tier "
            + "limit (fields limit, limitMinor/limitCount, remainingMinor/remainingCount, resetsAt, kycTier, "
            + "nextTier; remainingMinor is the most that passes NOW, the same figure as GET /me/limits "
            + "sendableNowMinor — for per_payment it can be below limitMinor; checked BEFORE step-up, so a "
            + "payment that will be refused never gets step_up_required). Also: key reused with a different "
            + "body (idempotency_conflict), or the transfer was refused (errorCode core_rejected). When the "
            + "core refuses it, the detail is ALWAYS one of three fixed sentences, never the core's wording: "
            + "not enough money in the sender's account; the receiving account cannot accept this payment "
            + "(any block on the recipient's side, a recipient the core does not know, or one in another "
            + "currency — deliberately indistinguishable); or the generic decline for everything else. One "
            + "more refusal never reaches the core: a request whose currency is not this cell's currency is "
            + "refused with its own detail naming both currencies (a client error — send the cell's "
            + "currency). Show the detail; do not parse it";

    private final MovementService movementService;

    @PostMapping("/deposit")
    @Operation(summary = "Deposit into the caller's own account",
            description = "A test cash-in: it credits the wallet with no source of funds behind it, so it "
                    + "is OFF unless the deployment switches it on (staging does; production does not). "
                    + "Off, every call answers 403 deposit_disabled before anything is read or written — "
                    + "no idempotency record, no ledger row, no core call. Fund a production wallet "
                    + "through a real cash-in channel, not this endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Deposits are switched off in this deployment "
                    + "(errorCode deposit_disabled — permanent for the deployment, do not retry), or the "
                    + "account does not belong to the caller (errorCode account_ownership_mismatch)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "Deposits disabled", value = """
                                            {"type":"about:blank","title":"Deposits unavailable","status":403,
                                             "detail":"Deposits are not available in this deployment.",
                                             "errorCode":"deposit_disabled"}
                                            """),
                                    @ExampleObject(name = "Not your account", value = """
                                            {"type":"about:blank","title":"Not your account","status":403,
                                             "detail":"You can only access accounts that belong to you.",
                                             "errorCode":"account_ownership_mismatch"}
                                            """)})),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance, wrong currency). Deposits are not "
                    + "subject to the per-tier payment limits.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many /transactions/* requests from this "
                    + "customer (errorCode rate_limited); retry after Retry-After seconds. A deposit on a "
                    + "deployment with deposits switched off is answered 403 deposit_disabled BEFORE this "
                    + "budget and never spends it",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Rate limited", value = RATE_LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "502", description = CORE_REFUSED_US_DESCRIPTION + ". On a "
                    + "deposit this is also what a cell provisioned WITHOUT customer deposits at the core answers "
                    + "while the deposit switch is on: the core's own lock refusing it",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Core refused this service", value = CORE_REFUSED_US))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        IdempotencyService.Result<TransactionResponse> result = movementService.deposit(
                UUID.fromString(jwt.getSubject()), idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw from the caller's own account",
            description = "At or above the caller's KYC-tier step-up threshold this returns 403 "
                    + "step_up_required with a txnFp; approve it via /auth/step-up/request + /verify and "
                    + "retry with the returned token in X-Step-Up-Token. The caller's per-tier payment limits "
                    + "(GET /me/limits) are checked FIRST: a payment over them is 422 limit_exceeded and never "
                    + "asks for an approval.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token, or an invalid / "
                    + "already-used / wrong-transaction X-Step-Up-Token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller; step-up "
                    + "approval is required (errorCode step_up_required, txnFp echoed); or the bank has blocked "
                    + "debits from this account (errorCode account_restricted — never retry automatically: show the "
                    + "detail; a new attempt can only succeed once the bank lifts the block). A block the core "
                    + "already lists is refused before anything is recorded, so a retry with the SAME key after the "
                    + "block is lifted runs the payment; a key that already ran answers its recorded outcome. "
                    + "The per-tier limits are checked before the block, so a payment that is ALSO over a limit "
                    + "is answered 422 limit_exceeded; the block shows on the next attempt within the limits",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "Step-up required", value = STEP_UP_REQUIRED),
                                    @ExampleObject(name = "Account restricted", value = ACCOUNT_RESTRICTED)
                            })),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = LIMITS_422_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "Daily limit", value = LIMIT_DAILY_EXAMPLE),
                                    @ExampleObject(name = "Per-payment limit", value = LIMIT_PER_PAYMENT_EXAMPLE),
                                    @ExampleObject(name = "Payments per day", value = LIMIT_COUNT_EXAMPLE),
                                    @ExampleObject(name = "Core rejected", value = CORE_REJECTED_EXAMPLE)
                            })),
            @ApiResponse(responseCode = "429", description = "Too many /transactions/* requests from this "
                    + "customer (errorCode rate_limited); retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Rate limited", value = RATE_LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "502", description = CORE_REFUSED_US_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Core refused this service", value = CORE_REFUSED_US))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable "
                    + "(core_banking_unavailable), or the caller's KYC tier could not be read for a decision "
                    + "that would refuse (kyc_tier_unavailable). Retry unchanged.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody WithdrawRequest request) {
        IdempotencyService.Result<TransactionResponse> result = movementService.withdraw(
                UUID.fromString(jwt.getSubject()), idempotencyKey, request, stepUpToken);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer from the caller's own account to another account",
            description = "At or above the caller's KYC-tier step-up threshold this returns 403 "
                    + "step_up_required with a txnFp; approve it via /auth/step-up/request + /verify and "
                    + "retry with the returned token in X-Step-Up-Token. The caller's per-tier payment limits "
                    + "(GET /me/limits) are checked FIRST: a payment over them is 422 limit_exceeded and never "
                    + "asks for an approval.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token, or an invalid / "
                    + "already-used / wrong-transaction X-Step-Up-Token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Source account does not belong to the caller; "
                    + "step-up approval is required (errorCode step_up_required, txnFp echoed); or the bank has "
                    + "blocked debits from the SOURCE account (errorCode account_restricted — never retry "
                    + "automatically: show the detail; a new attempt can only succeed once the bank lifts the "
                    + "block; a block the core already lists is refused before anything is recorded). A block on "
                    + "the RECIPIENT's account is not the sender's restriction and answers 422 core_rejected instead. "
                    + "The per-tier limits are checked before the block, so a payment that is ALSO over a limit "
                    + "is answered 422 limit_exceeded; the block shows on the next attempt within the limits",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "Step-up required", value = STEP_UP_REQUIRED),
                                    @ExampleObject(name = "Account restricted", value = ACCOUNT_RESTRICTED)
                            })),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = TRANSFER_422_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = {
                                    @ExampleObject(name = "Daily limit", value = LIMIT_DAILY_EXAMPLE),
                                    @ExampleObject(name = "Per-payment limit", value = LIMIT_PER_PAYMENT_EXAMPLE),
                                    @ExampleObject(name = "Payments per day", value = LIMIT_COUNT_EXAMPLE),
                                    @ExampleObject(name = "Not enough money", value = """
                                            {"type":"about:blank","title":"Request refused","status":422,
                                             "detail":"There is not enough money in your account for this payment.",
                                             "errorCode":"core_rejected"}
                                            """),
                                    @ExampleObject(name = "Recipient cannot receive", value = """
                                            {"type":"about:blank","title":"Request refused","status":422,
                                             "detail":"The receiving account cannot accept this payment.",
                                             "errorCode":"core_rejected"}
                                            """),
                                    @ExampleObject(name = "Declined", value = """
                                            {"type":"about:blank","title":"Request refused","status":422,
                                             "detail":"The banking system refused this payment.",
                                             "errorCode":"core_rejected"}
                                            """),
                                    @ExampleObject(name = "Not the cell's currency", value = """
                                            {"type":"about:blank","title":"Request refused","status":422,
                                             "detail":"Amount currency KES does not match the cell currency USD",
                                             "errorCode":"core_rejected"}
                                            """)
                            })),
            @ApiResponse(responseCode = "429", description = "Too many /transactions/* requests from this "
                    + "customer (errorCode rate_limited); retry after Retry-After seconds",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Rate limited", value = RATE_LIMITED_EXAMPLE))),
            @ApiResponse(responseCode = "502", description = CORE_REFUSED_US_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Core refused this service", value = CORE_REFUSED_US))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable "
                    + "(core_banking_unavailable), or the caller's KYC tier could not be read for a decision "
                    + "that would refuse (kyc_tier_unavailable). Retry unchanged.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @Valid @RequestBody TransferRequest request) {
        IdempotencyService.Result<TransactionResponse> result = movementService.transfer(
                UUID.fromString(jwt.getSubject()), idempotencyKey, request, stepUpToken);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @ExceptionHandler(PaymentLimitExceededException.class)
    public ResponseEntity<ProblemDetail> limitExceeded(PaymentLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(LimitExceededProblem.of(ex.refusal()));
    }

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<ProblemDetail> stepUpRequired(StepUpRequiredException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        body.setType(URI.create("about:blank"));
        body.setTitle("Approval required");
        body.setDetail("This amount needs SMS approval. Request a code, verify it, and retry "
                + "with the X-Step-Up-Token header.");
        body.setProperty("errorCode", "step_up_required");
        // The server-computed fingerprint the app passes to /auth/step-up/verify.
        body.setProperty("txnFp", ex.getTxnFp());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
