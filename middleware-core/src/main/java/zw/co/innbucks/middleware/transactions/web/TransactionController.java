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
import zw.co.innbucks.middleware.transactions.AccountOwnershipException;
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

    private final MovementService movementService;

    @PostMapping("/deposit")
    @Operation(summary = "Deposit into the caller's own account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance, wrong currency)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
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
    @Operation(summary = "Withdraw from the caller's own account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request) {
        IdempotencyService.Result<TransactionResponse> result = movementService.withdraw(
                UUID.fromString(jwt.getSubject()), idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer from the caller's own account to another account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement outcome (SUCCESS / PROCESSING / FAILED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(value = COMMON_RESPONSES))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Source account does not belong to the caller",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "An identical request is still in progress",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Key reused with a different body, or the core "
                    + "rejected the movement (e.g. insufficient balance, unknown destination)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:write')")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        IdempotencyService.Result<TransactionResponse> result = movementService.transfer(
                UUID.fromString(jwt.getSubject()), idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @ExceptionHandler(AccountOwnershipException.class)
    public ResponseEntity<ProblemDetail> ownership(AccountOwnershipException ex) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        body.setType(URI.create("about:blank"));
        body.setTitle("Not your account");
        body.setDetail("You can only move money from accounts that belong to you.");
        body.setProperty("errorCode", "account_ownership_mismatch");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
