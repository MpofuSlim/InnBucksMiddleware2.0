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
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementLine;
import zw.co.innbucks.middleware.statement.StatementMoney;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link StatementDocument} as an A4 PDF — header block, summary
 * strip, transaction table, provenance footer. Formatting only: every number
 * on the page was computed by the assembler, so this class contains no
 * arithmetic that could disagree with the JSON or CSV renderings.
 *
 * <p>Fonts are the PDF built-in Helvetica family: nothing to embed, nothing
 * to license, and the Latin repertoire covers the names this cell serves. If
 * a market with a non-Latin script ever onboards, this is the class that
 * grows a font, not the model.
 */
public final class PdfStatementRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm (zzz)", Locale.ENGLISH);

    private static final Font BRAND = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA, 13, Color.DARK_GRAY);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.DARK_GRAY);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font TABLE_HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font CELL = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font CELL_MUTED = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
    private static final Font FOOTNOTE = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.DARK_GRAY);

    private static final Color HEADER_BACKGROUND = new Color(31, 41, 55);
    private static final Color ROW_STRIPE = new Color(243, 244, 246);

    private PdfStatementRenderer() {
    }

    public static byte[] render(StatementDocument statement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 40, 44);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(heading(statement));
            document.add(metaTable(statement));
            document.add(summaryTable(statement));
            document.add(linesTable(statement));
            document.add(footnote(statement));
            document.close();
        } catch (DocumentException e) {
            // Nothing here is caller input — a failure is a rendering bug.
            throw new IllegalStateException("Statement PDF rendering failed", e);
        }
        return out.toByteArray();
    }

    private static PdfPTable heading(StatementDocument statement) {
        PdfPTable heading = new PdfPTable(2);
        heading.setWidthPercentage(100);
        heading.setSpacingAfter(10);
        PdfPCell brand = borderless(new Phrase("InnBucks", BRAND));
        PdfPCell title = borderless(new Phrase("Account Statement", TITLE));
        title.setHorizontalAlignment(Element.ALIGN_RIGHT);
        heading.addCell(brand);
        heading.addCell(title);
        return heading;
    }

    private static PdfPTable metaTable(StatementDocument s) {
        PdfPTable meta = new PdfPTable(new float[] {1.2f, 3.0f, 1.2f, 3.0f});
        meta.setWidthPercentage(100);
        meta.setSpacingAfter(8);
        addMeta(meta, "Customer", s.customerName() == null ? "—" : s.customerName());
        addMeta(meta, "Mobile", s.msisdn() == null ? "—" : s.msisdn());
        addMeta(meta, "Account", s.accountId());
        addMeta(meta, "Currency", s.currencyCode());
        addMeta(meta, "Period", DATE.format(s.from()) + " to " + DATE.format(s.to()));
        addMeta(meta, "Generated", GENERATED.format(s.generatedAt().atZone(s.displayZone())));
        return meta;
    }

    private static void addMeta(PdfPTable meta, String label, String value) {
        meta.addCell(borderless(new Phrase(label, LABEL)));
        meta.addCell(borderless(new Phrase(value, VALUE)));
    }

    private static PdfPTable summaryTable(StatementDocument s) {
        PdfPTable summary = new PdfPTable(4);
        summary.setWidthPercentage(100);
        summary.setSpacingAfter(10);
        String ccy = s.currencyCode();
        addSummary(summary, "Opening balance", money(s.openingBalanceMinor(), ccy));
        addSummary(summary, "Money in", money(s.totalCreditsMinor(), ccy));
        addSummary(summary, "Money out", money(s.totalDebitsMinor(), ccy));
        addSummary(summary, "Closing balance", money(s.closingBalanceMinor(), ccy));
        return summary;
    }

    private static void addSummary(PdfPTable summary, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.setBackgroundColor(ROW_STRIPE);
        cell.setBorderColor(Color.WHITE);
        cell.addElement(new Paragraph(label, LABEL));
        cell.addElement(new Paragraph(value, VALUE));
        summary.addCell(cell);
    }

    private static PdfPTable linesTable(StatementDocument s) {
        PdfPTable table = new PdfPTable(new float[] {1.3f, 2.8f, 0.9f, 1.4f, 1.4f, 1.6f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : new String[] {"Date", "Description", "Txn ID", "Money in", "Money out", "Balance"}) {
            PdfPCell cell = new PdfPCell(new Phrase(header, TABLE_HEADER));
            cell.setBackgroundColor(HEADER_BACKGROUND);
            cell.setPadding(5);
            table.addCell(cell);
        }
        if (s.lines().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No transactions in this period.", CELL_MUTED));
            empty.setColspan(6);
            empty.setPadding(8);
            table.addCell(empty);
            return table;
        }
        String ccy = s.currencyCode();
        boolean stripe = false;
        for (StatementLine line : s.lines()) {
            Color background = stripe ? ROW_STRIPE : Color.WHITE;
            stripe = !stripe;
            Font font = line.reversed() ? CELL_MUTED : CELL;
            String description = line.reversed() ? line.narrative() + " (reversed)" : line.narrative();
            boolean credit = line.direction() == TransactionDirection.CREDIT;
            table.addCell(cell(DATE.format(line.date()), font, background, Element.ALIGN_LEFT));
            table.addCell(cell(description == null ? "" : description, font, background, Element.ALIGN_LEFT));
            table.addCell(cell(line.coreId(), font, background, Element.ALIGN_LEFT));
            table.addCell(cell(credit ? money(line.amountMinor(), ccy) : "", font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(credit ? "" : money(line.amountMinor(), ccy), font, background, Element.ALIGN_RIGHT));
            table.addCell(cell(money(line.balanceAfterMinor(), ccy), font, background, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private static Paragraph footnote(StatementDocument s) {
        Paragraph note = new Paragraph(
                "Entries as recorded by the core banking system, including activity that did not "
                        + "originate in the InnBucks app (interest, fees, branch transactions). "
                        + "Reversed entries are shown for completeness and do not affect balances or "
                        + "totals. All amounts in " + s.currencyCode() + ".",
                FOOTNOTE);
        note.setSpacingBefore(10);
        return note;
    }

    private static PdfPCell cell(String text, Font font, Color background, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(background);
        cell.setPadding(5);
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

    private static String money(long signedMinor, String currencyCode) {
        return StatementMoney.format(signedMinor, currencyCode);
    }
}
