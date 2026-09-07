package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementRequestException;
import zw.co.innbucks.middleware.statement.StatementService;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;
import zw.co.innbucks.middleware.statement.render.CsvStatementRenderer;
import zw.co.innbucks.middleware.statement.render.PdfStatementRenderer;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * The fixed-period statement DOCUMENT for one of the caller's accounts — the
 * thing a customer downloads, emails or prints, with anchored opening and
 * closing balances. The paged feed on {@code /me/accounts/{id}/transactions}
 * stays what a scrolling screen consumes; this endpoint is what a bank, a
 * landlord or an embassy is shown.
 */
@RestController
@RequestMapping("/me")
@Validated
@Tag(name = "me", description = "Current-customer endpoints. Identity is taken from the Bearer JWT.")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/accounts/{accountId}/statement")
    @Operation(summary = "Account statement for a fixed period, as JSON, PDF or CSV",
            description = "A document, not a feed: entries OLDEST first with opening/closing "
                    + "balances anchored on the core banking system's own running balances, plus "
                    + "money-in/out totals. Sourced from the core, so it includes interest, fees "
                    + "and branch activity that never crossed this API, and reconciles against the "
                    + "balance on /me/accounts. A movement still being reconciled has no confirmed "
                    + "core entry yet and appears only once it settles. PDF and CSV are download "
                    + "renderings of the SAME document — the numbers cannot differ from the JSON.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement returned (JSON, PDF or CSV per ?format=)",
                    content = @Content(schema = @Schema(implementation = StatementResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad period (to before from, longer than the "
                    + "ceiling) or unknown format — errorCode statement_period_invalid / "
                    + "statement_period_too_long / statement_format_invalid",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Account does not belong to the caller",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No registered customer behind this token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Period valid but too many entries to render "
                    + "inline — errorCode statement_too_large; ask for a shorter period",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Core banking system unreachable, or it "
                    + "reported no running balances so honest balances cannot be established "
                    + "(errorCode statement_unavailable)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PreAuthorize("hasAuthority('SCOPE_customer:read')")
    public ResponseEntity<?> statement(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "From /me/accounts.", example = "3f0d1c2e-…:wallet")
            @PathVariable String accountId,
            @Parameter(description = "Inclusive period start, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive period end, ISO yyyy-MM-dd.", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Rendering: json (default), pdf or csv.", example = "pdf",
                    schema = @Schema(allowableValues = {"json", "pdf", "csv"}, defaultValue = "json"))
            @RequestParam(defaultValue = "json") String format) {

        String rendering = format.toLowerCase(Locale.ROOT);
        if (!rendering.equals("json") && !rendering.equals("pdf") && !rendering.equals("csv")) {
            throw StatementRequestException.invalidFormat(format);
        }

        StatementDocument document = statementService.statementFor(
                UUID.fromString(jwt.getSubject()), accountId, from, to);

        return switch (rendering) {
            case "pdf" -> download(PdfStatementRenderer.render(document),
                    MediaType.APPLICATION_PDF, filename(document, "pdf"));
            case "csv" -> download(CsvStatementRenderer.render(document),
                    MediaType.parseMediaType("text/csv;charset=UTF-8"), filename(document, "csv"));
            default -> ResponseEntity.ok(StatementResponse.of(document));
        };
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * Colon-free (the accountId contains one, and a colon breaks the filename
     * on half the operating systems the download lands on), identified by the
     * same last-4 tail the app already shows for the account.
     */
    private static String filename(StatementDocument document, String extension) {
        String alnum = document.accountId().replaceAll("[^A-Za-z0-9]", "");
        String tail = alnum.length() >= 4 ? alnum.substring(alnum.length() - 4) : alnum;
        return "innbucks-statement-" + tail + "-" + document.from() + "-" + document.to() + "." + extension;
    }

    @ExceptionHandler(StatementRequestException.class)
    public ResponseEntity<ProblemDetail> badRequest(StatementRequestException ex) {
        HttpStatus status = ex.tooLarge() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        return problem(status, "Statement not producible as asked", ex.getMessage(), ex.errorCode());
    }

    @ExceptionHandler(StatementUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(StatementUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Statement unavailable",
                ex.getMessage(), "statement_unavailable");
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(ProfileNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not registered",
                "We couldn't find a registered account for this login. Please register first.",
                "customer_not_registered");
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
