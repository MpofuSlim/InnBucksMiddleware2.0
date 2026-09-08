package zw.co.innbucks.middleware.statement.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementRequestException;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.render.CsvStatementRenderer;
import zw.co.innbucks.middleware.statement.render.LoanCsvStatementRenderer;
import zw.co.innbucks.middleware.statement.render.LoanPdfStatementRenderer;
import zw.co.innbucks.middleware.statement.render.PdfStatementRenderer;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The one place a statement rendering becomes an HTTP response, shared by the
 * customer and console controllers so the surfaces cannot drift on content
 * types, filenames or which formats exist.
 */
final class StatementDownloads {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private StatementDownloads() {
    }

    /** Lower-cased, validated rendering name; throws the stable 400 for anything else. */
    static String rendering(String format) {
        String rendering = format.toLowerCase(Locale.ROOT);
        if (!rendering.equals("json") && !rendering.equals("pdf") && !rendering.equals("csv")) {
            throw StatementRequestException.invalidFormat(format);
        }
        return rendering;
    }

    static ResponseEntity<?> respond(String rendering, StatementDocument document) {
        String base = filename("innbucks-statement-", document.accountId(), document.from(), document.to());
        return switch (rendering) {
            case "pdf" -> download(PdfStatementRenderer.render(document), MediaType.APPLICATION_PDF, base + ".pdf");
            case "csv" -> download(CsvStatementRenderer.render(document), CSV, base + ".csv");
            default -> ResponseEntity.ok(StatementResponse.of(document));
        };
    }

    static ResponseEntity<?> respondLoan(String rendering, LoanStatementDocument document) {
        String base = filename("innbucks-loan-statement-", document.accountNumber(), document.from(), document.to());
        return switch (rendering) {
            case "pdf" -> download(LoanPdfStatementRenderer.render(document), MediaType.APPLICATION_PDF, base + ".pdf");
            case "csv" -> download(LoanCsvStatementRenderer.render(document), CSV, base + ".csv");
            default -> ResponseEntity.ok(LoanStatementResponse.of(document));
        };
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * Colon-free (the customer accountId contains one, and a colon breaks the
     * filename on half the operating systems the download lands on),
     * identified by the last-4 tail of whatever identifies the account on
     * this surface. A life-of-loan statement has no start date and says so.
     */
    private static String filename(String prefix, String accountId, LocalDate from, LocalDate to) {
        String alnum = accountId.replaceAll("[^A-Za-z0-9]", "");
        String tail = alnum.length() >= 4 ? alnum.substring(alnum.length() - 4) : alnum;
        return prefix + tail + "-" + (from == null ? "inception" : from) + "-" + to;
    }
}
