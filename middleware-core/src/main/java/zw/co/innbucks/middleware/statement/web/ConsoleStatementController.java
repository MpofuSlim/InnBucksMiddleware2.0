package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.ratelimit.ClientIpResolver;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementRequestException;
import zw.co.innbucks.middleware.statement.StatementService;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.loan.LoanStatementService;

import java.net.URI;
import java.time.LocalDate;

/**
 * The BANK-ISSUED statements, for the back-office console (the Mifos web
 * app): one for deposit accounts (savings, fixed and recurring deposits —
 * the same document, same balance policy and same renderings as the
 * customer's {@code /me/accounts/{id}/statement}), one for loans.
 *
 * <p><b>Auth is the operator's own Fineract credential, verified BY
 * Fineract.</b> The console is a browser SPA that holds nothing but the
 * operator's Basic credentials, and this middleware deliberately has no
 * operator identity. So Spring Security permits this path
 * ({@code SecurityConfig}) and the credential is forwarded — to Fineract
 * only, over the private cell network — where Fineract answers both "who is
 * this" and "may they read this account" on the same call. A 200 from
 * Fineract IS the authorisation. No operator table, no session, no shared
 * secret in a browser.
 *
 * <p>The 401 here deliberately carries no {@code WWW-Authenticate} header: a
 * browser would answer one with its native Basic-auth popup over the Mifos
 * UI, which handles its own login.
 */
@RestController
@RequestMapping("/console")
@Validated
@Tag(name = "console", description = "Back-office endpoints for the CBS console. Authenticated by the "
        + "OPERATOR'S Fineract Basic credential, which this middleware verifies against Fineract — "
        + "send the same Authorization header the console already sends to Fineract itself.")
public class ConsoleStatementController {

    private final StatementService statementService;
    private final LoanStatementService loanStatementService;
    private final ConsoleStatementRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ConsoleStatementController(StatementService statementService,
                                      LoanStatementService loanStatementService,
                                      ConsoleStatementRateLimiter rateLimiter,
                                      ClientIpResolver clientIpResolver) {
        this.statementService = statementService;
        this.loanStatementService = loanStatementService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/savings-accounts/{savingsAccountId}/statement")
    @Operation(summary = "Bank-issued deposit-account statement for a fixed period, as JSON, PDF or CSV",
            description = "The document a branch hands a customer, for a SAVINGS, FIXED DEPOSIT or "
                    + "RECURRING DEPOSIT account — all three live in the same account table in the "
                    + "core and statement identically; accountType in the response says which, and "
                    + "titles the PDF/CSV. Keyed by the FINERACT savings account id (the number the "
                    + "console already displays), authorised by the operator's own Fineract "
                    + "credential: this API forwards the presented Basic header to Fineract, and "
                    + "renders the statement only if Fineract accepts the credential AND lets that "
                    + "operator read the account. Entries OLDEST first, opening/closing balances "
                    + "anchored on the core's own running balances. Same document model as the "
                    + "customer statement — the numbers cannot differ.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement returned (JSON, PDF or CSV per ?format=)",
                    content = @Content(schema = @Schema(implementation = StatementResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad period or unknown format — errorCode "
                    + "statement_period_invalid / statement_period_too_long / statement_format_invalid",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "No Basic credential presented, or Fineract "
                    + "rejected it — errorCode operator_credentials_required / operator_unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Account not found OR not readable by this "
                    + "operator — deliberately indistinguishable (errorCode account_not_accessible)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "errorCode statement_too_large — narrow the period",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this source",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable, no operator "
                    + "gateway on this cell, or no running balances (errorCode statement_unavailable)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @SecurityRequirements({})
    public ResponseEntity<?> statement(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "The Fineract savings account id (savings, fixed or recurring "
                    + "deposit), e.g. 17.", example = "17")
            @PathVariable String savingsAccountId,
            @Parameter(description = "Inclusive period start, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive period end, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Rendering: json (default), pdf or csv.", example = "pdf",
                    schema = @Schema(allowableValues = {"json", "pdf", "csv"}, defaultValue = "json"))
            @RequestParam(defaultValue = "json") String format) {

        ResponseEntity<ProblemDetail> refused = throttleOrCredentialProblem(request, authorization);
        if (refused != null) {
            return refused;
        }
        String rendering = StatementDownloads.rendering(format);
        StatementDocument document = statementService.statementForOperator(
                new OperatorCredential(authorization), savingsAccountId, from, to);
        return StatementDownloads.respond(rendering, document);
    }

    @GetMapping("/loans/{loanId}/statement")
    @Operation(summary = "Bank-issued LOAN statement, as JSON, PDF or CSV",
            description = "The loan statement business-banking managers and credit work from: the "
                    + "loan's terms, the PRINCIPAL balance walked across the period, period totals "
                    + "(disbursed, repaid with its principal/interest/fee/penalty split, waived, "
                    + "written off), the position as at generation, and every transaction. Keyed by "
                    + "the FINERACT loan id (the number in the console URL); authorised by the "
                    + "operator's own Fineract credential, and — unlike the deposit statement — EVERY "
                    + "read rides that credential, so Fineract's READ_LOAN permission is enforced on "
                    + "each call. `from` and `to` are OPTIONAL: no `from` means the life of the loan "
                    + "(the usual request), no `to` means today; there is no period ceiling, only the "
                    + "entry ceiling. Accrual bookkeeping is not shown. Entries OLDEST first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement returned (JSON, PDF or CSV per ?format=)",
                    content = @Content(schema = @Schema(implementation = LoanStatementResponse.class))),
            @ApiResponse(responseCode = "400", description = "to before from, or unknown format — errorCode "
                    + "statement_period_invalid / statement_format_invalid",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "No Basic credential presented, or Fineract "
                    + "rejected it — errorCode operator_credentials_required / operator_unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Loan not found OR not readable by this "
                    + "operator — deliberately indistinguishable (errorCode account_not_accessible)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "errorCode statement_too_large — ask for a "
                    + "shorter, more recent period",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this source",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable, no operator "
                    + "gateway on this cell, or no principal balances (errorCode statement_unavailable)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @SecurityRequirements({})
    public ResponseEntity<?> loanStatement(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "The Fineract loan id, e.g. 9.", example = "9")
            @PathVariable String loanId,
            @Parameter(description = "Inclusive period start, ISO yyyy-MM-dd. Omit for the life of the loan.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive period end, ISO yyyy-MM-dd. Omit for today.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Rendering: json (default), pdf or csv.", example = "pdf",
                    schema = @Schema(allowableValues = {"json", "pdf", "csv"}, defaultValue = "json"))
            @RequestParam(defaultValue = "json") String format) {

        ResponseEntity<ProblemDetail> refused = throttleOrCredentialProblem(request, authorization);
        if (refused != null) {
            return refused;
        }
        String rendering = StatementDownloads.rendering(format);
        LoanStatementDocument document = loanStatementService.statementForOperator(
                new OperatorCredential(authorization), loanId, from, to);
        return StatementDownloads.respondLoan(rendering, document);
    }

    /**
     * Throttle BEFORE touching the credential: these endpoints forward
     * whatever they are handed to Fineract, and unmetered they would be an
     * operator-password oracle. One bucket per source across both documents
     * — a spray does not get a second budget by switching to loans.
     */
    private ResponseEntity<ProblemDetail> throttleOrCredentialProblem(HttpServletRequest request,
                                                                      String authorization) {
        if (!rateLimiter.tryConsume(clientIpResolver.resolve(request))) {
            return problemWithRetry();
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return problem(HttpStatus.UNAUTHORIZED, "Operator credentials required",
                    "Send the Fineract Basic Authorization header the console already uses.",
                    "operator_credentials_required");
        }
        return null;
    }

    private static ResponseEntity<ProblemDetail> problemWithRetry() {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        body.setType(URI.create("about:blank"));
        body.setTitle("Too many requests");
        body.setDetail("Slow down and retry shortly.");
        body.setProperty("errorCode", "too_many_requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(StatementRequestException.class)
    public ResponseEntity<ProblemDetail> badRequest(StatementRequestException ex) {
        HttpStatus status = ex.unprocessable() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        return problem(status, "Statement not producible as asked", ex.getMessage(), ex.errorCode());
    }

    @ExceptionHandler(StatementUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(StatementUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Statement unavailable",
                ex.getMessage(), "statement_unavailable");
    }

    /** Fineract said the credential itself is bad. */
    @ExceptionHandler(CoreAuthException.class)
    public ResponseEntity<ProblemDetail> operatorRejected(CoreAuthException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Operator not authenticated",
                "The core banking system rejected the presented credential.",
                "operator_unauthorized");
    }

    /**
     * Missing account and not-permitted account answer identically on
     * purpose — this endpoint must not become an account-enumeration oracle
     * cheaper than the core's own API.
     */
    @ExceptionHandler(CoreClientException.class)
    public ResponseEntity<ProblemDetail> accountNotAccessible(CoreClientException ex) {
        return problem(HttpStatus.NOT_FOUND, "Account not accessible",
                "No such account or loan, or this operator may not read it.",
                "account_not_accessible");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title,
                                                         String detail, String errorCode) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(URI.create("about:blank"));
        body.setTitle(title);
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
