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
                    + "rejected the movement (e.g. insufficient balance, wrong currency)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
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
                    + "retry with the returned token in X-Step-Up-Token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token, or an invalid / "
                    + "already-used / wrong-transaction X-Step-Up-Token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller, or step-up "
                    + "approval is required (errorCode step_up_required, txnFp echoed)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Step-up required", value = """
                                    {"type":"about:blank","title":"Approval required","status":403,
                                     "detail":"This amount needs SMS approval. Request a code, verify it, and retry with the X-Step-Up-Token header.",
                                     "errorCode":"step_up_required",
                                     "txnFp":"9f2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = CORE_REFUSED_US_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Core refused this service", value = CORE_REFUSED_US))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
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
                    + "retry with the returned token in X-Step-Up-Token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token, or an invalid / "
                    + "already-used / wrong-transaction X-Step-Up-Token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Source account does not belong to the caller, or "
                    + "step-up approval is required (errorCode step_up_required, txnFp echoed)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Step-up required", value = """
                                    {"type":"about:blank","title":"Approval required","status":403,
                                     "detail":"This amount needs SMS approval. Request a code, verify it, and retry with the X-Step-Up-Token header.",
                                     "errorCode":"step_up_required",
                                     "txnFp":"9f2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e9f0a2b4c6d8e0a1b3c5d7e"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance, unknown destination)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = CORE_REFUSED_US_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Core refused this service", value = CORE_REFUSED_US))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
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
