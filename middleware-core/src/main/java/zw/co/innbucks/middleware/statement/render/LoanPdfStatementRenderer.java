package zw.co.innbucks.middleware.statement.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementMoney;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.loan.LoanStatementLine;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link LoanStatementDocument} as a LANDSCAPE A4 PDF — header,
 * loan terms, period totals, the current position, the transaction table
 * (with the principal / interest / fees / penalties split a repayment
 * carries) and a provenance footer. Formatting only: every number on the
 * page was computed by the assembler or reported by the core, so this class
 * contains no arithmetic that could disagree with the JSON or CSV renderings.
 *
 * <p>Landscape because a loan line has eight numeric columns; the deposit
 * statement's portrait grid would not hold them at a readable size.
 */
public final class LoanPdfStatementRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm (zzz)", Locale.ENGLISH);

    private static final Font BRAND = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA, 13, Color.DARK_GRAY);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.DARK_GRAY);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.DARK_GRAY);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font TABLE_HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
    private static final Font CELL = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
    private static final Font CELL_MUTED = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);
    private static final Font FOOTNOTE = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.DARK_GRAY);

    private static final Color HEADER_BACKGROUND = new Color(31, 41, 55);
    private static final Color ROW_STRIPE = new Color(243, 244, 246);

    private LoanPdfStatementRenderer() {
    }

    public static byte[] render(LoanStatementDocument statement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 40, 44);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(heading(statement));
            document.add(metaTable(statement));
            document.add(totalsTable(statement));
            if (statement.position() != null) {
                document.add(positionTable(statement));
            }
            document.add(linesTable(statement));
            document.add(footnote(statement));
            document.close();
        } catch (DocumentException e) {
            // Nothing here is caller input — a failure is a rendering bug.
            throw new IllegalStateException("Loan statement PDF rendering failed", e);
        }
        return out.toByteArray();
    }

    private static PdfPTable heading(LoanStatementDocument statement) {
        PdfPTable heading = new PdfPTable(2);
        heading.setWidthPercentage(100);
        heading.setSpacingAfter(10);
        PdfPCell brand = borderless(new Phrase("InnBucks", BRAND));
        PdfPCell title = borderless(new Phrase(statement.title(), TITLE));
        title.setHorizontalAlignment(Element.ALIGN_RIGHT);
        heading.addCell(brand);
        heading.addCell(title);
        return heading;
    }

    private static PdfPTable metaTable(LoanStatementDocument s) {
        PdfPTable meta = new PdfPTable(new float[] {1.1f, 2.4f, 1.1f, 2.4f, 1.1f, 2.4f});
        meta.setWidthPercentage(100);
        meta.setSpacingAfter(8);
        String ccy = s.currencyCode();
        addMeta(meta, "Borrower", dash(s.borrowerName()));
        addMeta(meta, "Mobile", dash(s.borrowerMobile()));
        addMeta(meta, "Loan account", s.accountNumber());
        addMeta(meta, "Product", dash(s.productName()));
        addMeta(meta, "Status", s.status());
        addMeta(meta, "Currency", ccy);
        addMeta(meta, "Principal", s.principalMinor() == null ? "—" : money(s.principalMinor(), ccy));
        addMeta(meta, "Interest rate", s.annualInterestRate() == null ? "—"
                : s.annualInterestRate().setScale(2, RoundingMode.HALF_UP).toPlainString() + "% p.a.");
        addMeta(meta, "Term", dash(s.termDescription()));
        addMeta(meta, "Disbursed on", s.disbursedOn() == null ? "—" : DATE.format(s.disbursedOn()));
        addMeta(meta, "Maturity", s.maturityDate() == null ? "—" : DATE.format(s.maturityDate()));
        addMeta(meta, "Period", period(s));
        addMeta(meta, "Generated", GENERATED.format(s.generatedAt().atZone(s.displayZone())));
        addMeta(meta, "Loan ID", s.loanId());
        addMeta(meta, "Reference", dash(s.externalId()));
        // OpenPDF drops a half-filled last row silently; keep the grid honest
        // whatever the pair count becomes.
        meta.completeRow();
        return meta;
    }

    private static String period(LoanStatementDocument s) {
        return (s.from() == null ? "Since inception" : DATE.format(s.from()))
                + " to " + DATE.format(s.to());
    }

    private static void addMeta(PdfPTable meta, String label, String value) {
        meta.addCell(borderless(new Phrase(label, LABEL)));
        meta.addCell(borderless(new Phrase(value, VALUE)));
    }

    private static PdfPTable totalsTable(LoanStatementDocument s) {
        PdfPTable totals = new PdfPTable(4);
        totals.setWidthPercentage(100);
        totals.setSpacingAfter(8);
        String ccy = s.currencyCode();
        LoanStatementDocument.Totals t = s.totals();
        addSummary(totals, "Opening principal", money(s.openingPrincipalMinor(), ccy));
        addSummary(totals, "Disbursed", money(t.disbursedMinor(), ccy));
        addSummary(totals, "Principal repaid", money(t.principalRepaidMinor(), ccy));
        addSummary(totals, "Closing principal", money(s.closingPrincipalMinor(), ccy));
        addSummary(totals, "Total repaid", money(t.repaidMinor(), ccy));
        addSummary(totals, "Interest paid", money(t.interestRepaidMinor(), ccy));
        addSummary(totals, "Fees & penalties paid",
                money(t.feesRepaidMinor() + t.penaltiesRepaidMinor(), ccy));
        addSummary(totals, "Waived / written off",
                money(t.waivedMinor(), ccy) + " / " + money(t.writtenOffMinor(), ccy));
        totals.completeRow();
        return totals;
    }

    private static PdfPTable positionTable(LoanStatementDocument s) {
        OperatorLoanView.LoanPosition p = s.position();
        String ccy = s.currencyCode();
        PdfPTable position = new PdfPTable(6);
        position.setWidthPercentage(100);
        position.setSpacingAfter(10);
        PdfPCell caption = borderless(new Phrase("Position as at "
                + GENERATED.format(s.generatedAt().atZone(s.displayZone())), SECTION));
        caption.setColspan(6);
        position.addCell(caption);
        addSummary(position, "Principal outstanding", money(p.principalOutstandingMinor(), ccy));
        addSummary(position, "Interest outstanding", money(p.interestOutstandingMinor(), ccy));
        addSummary(position, "Fees outstanding", money(p.feesOutstandingMinor(), ccy));
        addSummary(position, "Penalties outstanding", money(p.penaltiesOutstandingMinor(), ccy));
        addSummary(position, "Total outstanding", money(p.totalOutstandingMinor(), ccy));
        addSummary(position, "Overdue", money(p.totalOverdueMinor(), ccy)
                + (p.overdueSince() == null ? "" : " (since " + DATE.format(p.overdueSince()) + ")"));
        position.completeRow();
        return position;
    }

    private static void addSummary(PdfPTable table, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.setBackgroundColor(ROW_STRIPE);
        cell.setBorderColor(Color.WHITE);
        cell.addElement(new Paragraph(label, LABEL));
        cell.addElement(new Paragraph(value, VALUE));
        table.addCell(cell);
    }

    private static PdfPTable linesTable(LoanStatementDocument s) {
        PdfPTable table = new PdfPTable(new float[] {1.2f, 2.6f, 0.8f, 1.3f, 1.3f, 1.2f, 1.0f, 1.0f, 1.5f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : new String[] {"Date", "Description", "Txn ID", "Amount", "Principal",
                "Interest", "Fees", "Penalties", "Principal balance"}) {
            PdfPCell cell = new PdfPCell(new Phrase(header, TABLE_HEADER));
            cell.setBackgroundColor(HEADER_BACKGROUND);
            cell.setPadding(5);
            table.addCell(cell);
        }
        if (s.lines().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No transactions in this period.", CELL_MUTED));
            empty.setColspan(9);
            empty.setPadding(8);
            table.addCell(empty);
            return table;
        }
        String ccy = s.currencyCode();
        boolean stripe = false;
        for (LoanStatementLine line : s.lines()) {
            Color background = stripe ? ROW_STRIPE : Color.WHITE;
            stripe = !stripe;
            boolean noEffect = line.reversed() || line.kind() == LoanEntryKind.NONE;
            Font font = noEffect ? CELL_MUTED : CELL;
            String description = line.reversed() ? line.narrative() + " (reversed)"
                    : line.kind() == LoanEntryKind.NONE ? line.narrative() + " (no balance effect)"
                    : line.narrative();
            table.addCell(cell(DATE.format(line.date()), font, background, Element.ALIGN_LEFT));
            table.addCell(cell(description == null ? "" : description, font, background, Element.ALIGN_LEFT));
            table.addCell(cell(line.coreId(), font, background, Element.ALIGN_LEFT));
            table.addCell(cell(money(line.amountMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(portion(line.principalMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(portion(line.interestMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(portion(line.feesMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(portion(line.penaltiesMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(money(line.principalBalanceAfterMinor(), ccy), font, background, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static Paragraph footnote(LoanStatementDocument s) {
        Paragraph note = new Paragraph(
                "Entries as recorded by the core banking system. Principal balance is the core's "
                        + "own principal outstanding after each entry; interest, fees and penalties "
                        + "outstanding are shown in the position block as at generation. Reversed "
                        + "entries are shown for completeness and do not affect balances or totals. "
                        + "Accrual bookkeeping is not shown. All amounts in " + s.currencyCode() + ".",
                FOOTNOTE);
        note.setSpacingBefore(10);
        return note;
    }

    private static PdfPCell cell(String text, Font font, Color background, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(background);
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        cell.setBorderColor(Color.WHITE);
        return cell;
    }

    private static PdfPCell borderless(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPaddingBottom(3);
        return cell;
    }

    private static String portion(Long minor, String ccy) {
        return minor == null ? "" : money(minor, ccy);
    }

    private static String money(long signedMinor, String currencyCode) {
        return StatementMoney.format(signedMinor, currencyCode);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
