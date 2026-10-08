package com.revesoft.tms.report;

import com.revesoft.tms.payment.PaymentService.Receipt;
import com.revesoft.tms.report.ReportTable.Column;
import com.revesoft.tms.report.ReportTable.SummaryItem;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link ReportTable} (and payment receipts) as PDF. Wide tables use A4 landscape. Every
 * page shows the organisation, the generation time and the page number.
 *
 * <p>Uses the built-in Helvetica font, so text is limited to Latin characters; the taka sign is
 * written as "Tk".
 */
@Component
public class PdfRenderer {

    public static final String CONTENT_TYPE = "application/pdf";

    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD);
    private static final Font ORG = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(60, 60, 60));
    private static final Font NORMAL = new Font(Font.HELVETICA, 9);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, Color.GRAY);
    private static final Color HEADER_BG = new Color(230, 230, 230);

    public byte[] render(ReportTable table, String orgName, String generatedAt) {
        Rectangle page = table.columns().size() > 6 ? PageSize.A4.rotate() : PageSize.A4;
        return document(page, orgName, generatedAt, doc -> {
            doc.add(new Paragraph(safe(orgName), ORG));
            doc.add(new Paragraph(safe(table.title()), TITLE));
            if (table.subtitle() != null) {
                doc.add(new Paragraph(safe(table.subtitle()), NORMAL));
            }
            doc.add(new Paragraph("Generated " + generatedAt, SMALL));
            doc.add(new Paragraph(" ", NORMAL));

            List<Column> cols = table.columns();
            PdfPTable t = new PdfPTable(cols.size());
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (Column c : cols) {
                PdfPCell cell = new PdfPCell(new Phrase(safe(c.label()), BOLD));
                cell.setBackgroundColor(HEADER_BG);
                cell.setHorizontalAlignment(align(c.type()));
                cell.setPadding(4);
                t.addCell(cell);
            }
            if (table.rows().isEmpty()) {
                PdfPCell empty = new PdfPCell(new Phrase("No data for the selected filters", NORMAL));
                empty.setColspan(cols.size());
                empty.setPadding(6);
                t.addCell(empty);
            }
            for (Map<String, Object> row : table.rows()) {
                addRow(t, cols, row, NORMAL, false);
            }
            if (table.totals() != null) {
                addRow(t, cols, table.totals(), BOLD, true);
            }
            doc.add(t);

            if (table.summary() != null) {
                doc.add(new Paragraph(" ", NORMAL));
                PdfPTable s = new PdfPTable(2);
                s.setWidthPercentage(45);
                s.setHorizontalAlignment(Element.ALIGN_LEFT);
                for (SummaryItem item : table.summary()) {
                    s.addCell(plain(safe(item.label()), BOLD, Element.ALIGN_LEFT));
                    s.addCell(plain(format(item.value(), item.type()), NORMAL, align(item.type())));
                }
                doc.add(s);
            }
        });
    }

    /** A one-page money receipt. */
    public byte[] receipt(Receipt r, String generatedAt) {
        return document(PageSize.A5, r.organization(), generatedAt, doc -> {
            String cur = currency(r.currency());
            Paragraph org = new Paragraph(safe(r.organization()).toUpperCase(Locale.ROOT), TITLE);
            org.setAlignment(Element.ALIGN_CENTER);
            doc.add(org);
            Paragraph title = new Paragraph("MONEY RECEIPT", ORG);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            doc.add(new Paragraph(" ", NORMAL));

            PdfPTable t = new PdfPTable(new float[] {35, 65});
            t.setWidthPercentage(100);
            line(t, "Receipt No", r.receiptNo());
            line(t, "Date", r.date() == null ? "" : r.date().toString());
            line(t, "Tenant", r.tenantName() + (r.tenantCode() == null || r.tenantCode().isBlank()
                    ? "" : " (" + r.tenantCode() + ")"));
            line(t, "Property / Unit", nz(r.propertyName()) + " - " + nz(r.unitNo()));
            line(t, "Invoice", r.invoiceCode() + " (" + r.billingMonth() + ")");
            line(t, "Invoice total", cur + " " + money(r.invoiceTotal()));
            line(t, "Amount paid", cur + " " + money(r.amountPaid()));
            line(t, "Method", r.method() == null ? "" : r.method().name().replace('_', ' '));
            if (r.referenceNo() != null && !r.referenceNo().isBlank()) {
                line(t, "Reference", r.referenceNo());
            }
            line(t, "Balance after", cur + " " + money(r.balanceAfter()));
            if (r.remarks() != null && !r.remarks().isBlank()) {
                line(t, "Remarks", r.remarks());
            }
            line(t, "Received by", r.receivedBy());
            doc.add(t);
            if (r.voided()) {
                doc.add(new Paragraph(" ", NORMAL));
                Paragraph v = new Paragraph("VOIDED: " + safe(nz(r.voidReason())),
                        new Font(Font.HELVETICA, 12, Font.BOLD, Color.RED));
                v.setAlignment(Element.ALIGN_CENTER);
                doc.add(v);
            }
        });
    }

    // ---------------------------------------------------------------- Helpers

    private interface Body {
        void write(Document doc) throws Exception;
    }

    private static byte[] document(Rectangle size, String orgName, String generatedAt, Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(size, 30, 30, 30, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer(safe(orgName), generatedAt));
            doc.open();
            body.write(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create the PDF", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String org;
        private final String generatedAt;

        Footer(String org, String generatedAt) {
            this.org = org;
            this.generatedAt = generatedAt;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 20;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                    new Phrase(org + " - generated " + generatedAt, SMALL), document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), SMALL), document.right(), y, 0);
        }
    }

    private static void addRow(PdfPTable t, List<Column> cols, Map<String, Object> values, Font font, boolean total) {
        for (Column c : cols) {
            PdfPCell cell = plain(format(values.get(c.key()), c.type()), font, align(c.type()));
            if (total) {
                cell.setBorderWidthTop(1.2f);
            }
            t.addCell(cell);
        }
    }

    private static void line(PdfPTable t, String label, String value) {
        PdfPCell l = plain(label, BOLD, Element.ALIGN_LEFT);
        l.setBorder(Rectangle.NO_BORDER);
        PdfPCell v = plain(safe(nz(value)), NORMAL, Element.ALIGN_LEFT);
        v.setBorder(Rectangle.NO_BORDER);
        t.addCell(l);
        t.addCell(v);
    }

    private static PdfPCell plain(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setPadding(3);
        return cell;
    }

    private static int align(String type) {
        return ReportTable.TEXT.equals(type) || ReportTable.DATE.equals(type) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT;
    }

    static String format(Object v, String type) {
        if (v == null) {
            return "";
        }
        return switch (type) {
            case ReportTable.MONEY -> money(toDecimal(v));
            case ReportTable.NUMBER -> new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                    .format(toDecimal(v));
            case ReportTable.PERCENT -> new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                    .format(toDecimal(v)) + "%";
            case ReportTable.DATE -> v instanceof LocalDate d ? d.toString() : safe(v.toString());
            default -> safe(v.toString());
        };
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String money(BigDecimal v) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                .format(v == null ? BigDecimal.ZERO : v);
    }

    private static String currency(String c) {
        return c == null || c.isBlank() ? "" : safe(c);
    }

    /** The standard PDF fonts only cover Latin-1; replace the taka sign and drop other characters. */
    static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("৳", "Tk").replace('•', '-').replace('–', '-').replace('—', '-');
        StringBuilder b = new StringBuilder(t.length());
        for (char ch : t.toCharArray()) {
            b.append(ch < 256 ? ch : '?');
        }
        return b.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
