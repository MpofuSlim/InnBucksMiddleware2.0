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

import java.net.URI;
import java.time.LocalDate;

/**
 * The BANK-ISSUED statement, for the back-office console (the Mifos web
 * app). Same document, same balance policy, same renderings as the
 * customer's {@code /me/accounts/{id}/statement} — produced from the one
 * {@link StatementDocument} model, so the two surfaces cannot disagree about
 * a number.
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
    private final ConsoleStatementRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ConsoleStatementController(StatementService statementService,
                                      ConsoleStatementRateLimiter rateLimiter,
                                      ClientIpResolver clientIpResolver) {
        this.statementService = statementService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/savings-accounts/{savingsAccountId}/statement")
    @Operation(summary = "Bank-issued account statement for a fixed period, as JSON, PDF or CSV",
            description = "The document a branch hands a customer. Keyed by the FINERACT savings "
                    + "account id (the number the console already displays), authorised by the "
                    + "operator's own Fineract credential: this API forwards the presented Basic "
                    + "header to Fineract, and renders the statement only if Fineract accepts the "
                    + "credential AND lets that operator read the account. Entries OLDEST first, "
                    + "opening/closing balances anchored on the core's own running balances. Same "
                    + "document model as the customer statement — the numbers cannot differ.")
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
            @Parameter(description = "The Fineract savings account id, e.g. 17.", example = "17")
            @PathVariable String savingsAccountId,
            @Parameter(description = "Inclusive period start, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive period end, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Rendering: json (default), pdf or csv.", example = "pdf",
                    schema = @Schema(allowableValues = {"json", "pdf", "csv"}, defaultValue = "json"))
            @RequestParam(defaultValue = "json") String format) {

        // Throttle BEFORE touching the credential: this endpoint forwards
        // whatever it is handed to Fineract, and unmetered it would be an
        // operator-password oracle.
        if (!rateLimiter.tryConsume(clientIpResolver.resolve(request))) {
            return problemWithRetry();
        }

        String rendering = StatementDownloads.rendering(format);
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return problem(HttpStatus.UNAUTHORIZED, "Operator credentials required",
                    "Send the Fineract Basic Authorization header the console already uses.",
                    "operator_credentials_required");
        }

        StatementDocument document = statementService.statementForOperator(
                new OperatorCredential(authorization), savingsAccountId, from, to);
        return StatementDownloads.respond(rendering, document);
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
                "No such savings account, or this operator may not read it.",
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
