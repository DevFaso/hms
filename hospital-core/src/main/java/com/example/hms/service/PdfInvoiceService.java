package com.example.hms.service;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.PaymentTransaction;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Invoice and payment-receipt PDFs. Labels come from the message bundle in the locale the
 * caller resolved; with no locale at all the product's first language, French.
 *
 * <p>QA D141: the invoice states the hospital, the dates, every amount in francs CFA, the
 * total, the amount paid, the balance and the status, and the confirmed payments; the
 * totals are always printed (a long invoice continues on a new page instead of losing
 * them), and text outside the fonts' WinAnsi encoding is written as '?' instead of failing
 * the whole document.
 */
@Service
@RequiredArgsConstructor
public class PdfInvoiceService {

    private static final float MARGIN = 50f;
    private static final float BOTTOM = 80f;
    private static final PDFont BODY = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final String GRAND_TOTAL = "pdf.invoice.grandTotal";
    private static final String AMOUNT_PAID = "pdf.invoice.amountPaid";
    private static final String BALANCE_DUE = "pdf.invoice.balanceDue";
    private static final String HOSPITAL = "pdf.invoice.hospital";
    private static final String PATIENT = "pdf.invoice.patient";

    private final MessageSource messageSource;

    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     Locale locale) {
        return generateInvoicePdf(invoice, items, List.of(), locale);
    }

    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     List<PaymentTransaction> payments,
                                     Locale locale) {
        return generateInvoicePdf(invoice, items, payments, List.of(), locale);
    }

    /** QA D974: with the discounts, waivers and credit notes printed between the lines and the total. */
    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     List<PaymentTransaction> payments,
                                     List<com.example.hms.model.InvoiceAdjustment> adjustments,
                                     Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get("pdf.invoice.title", safe(invoice.getInvoiceNumber())));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, hospitalName(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN,
                t.get("pdf.invoice.dates", t.fmt(invoice.getInvoiceDate()), t.fmt(invoice.getDueDate())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(PATIENT, patientLine(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.invoice.status", statusLabel(t, invoice)));
            w.qr(buildQrPng("hms://invoice/" + invoice.getId()));
            w.down(80);

            w.rule();
            w.down(14);
            w.row(BOLD,
                t.get("pdf.invoice.col.description"),
                t.get("pdf.invoice.col.category"),
                t.get("pdf.invoice.col.qty"),
                t.get("pdf.invoice.col.unitPrice"),
                t.get("pdf.invoice.col.total"));
            w.down(8);
            w.rule();
            for (var it : items) {
                w.ensureRoom(16);
                w.down(16);
                w.row(BODY,
                    designation(t, it),
                    categoryLabel(t, it.getItemCategory()),
                    it.getQuantity() != null ? String.valueOf(it.getQuantity()) : "",
                    money(it.getUnitPrice()),
                    money(lineTotal(it)));
            }

            for (var adjustment : adjustments) {
                w.ensureRoom(16);
                w.down(16);
                String kind = t.get("pdf.invoice.adjustment." + adjustment.getType().name());
                w.text(BODY, 10, MARGIN, adjustment.getReason() == null || adjustment.getReason().isBlank()
                    ? t.get("pdf.invoice.adjustmentLineNoReason", kind, money(adjustment.getAmount()))
                    : t.get("pdf.invoice.adjustmentLine", kind, shortReason(adjustment.getReason()),
                        money(adjustment.getAmount())));
            }

            BigDecimal total = nz(invoice.getTotalAmount());
            BigDecimal paid = nz(invoice.getAmountPaid());
            w.ensureRoom(80);
            w.down(18);
            w.rule();
            w.down(18);
            w.right(BOLD, 12, t.get(GRAND_TOTAL, money(total)));
            w.down(16);
            w.right(BODY, 11, t.get(AMOUNT_PAID, money(paid)));
            w.down(16);
            w.right(BOLD, 11, t.get(BALANCE_DUE, money(total.subtract(paid).max(BigDecimal.ZERO))));

            List<PaymentTransaction> confirmed = payments.stream()
                .filter(p -> p.getStatus() == null || p.getStatus() == PaymentStatus.CONFIRMED)
                .toList();
            if (!confirmed.isEmpty()) {
                w.ensureRoom(40);
                w.down(28);
                w.text(BOLD, 11, MARGIN, t.get("pdf.invoice.payments"));
                for (var p : confirmed) {
                    w.ensureRoom(16);
                    w.down(15);
                    w.text(BODY, 10, MARGIN, t.get("pdf.invoice.paymentLine",
                        t.fmt(p.getPaymentDate()), methodLabel(t, p), safe(p.getReferenceNumber()),
                        money(p.getAmount())));
                }
            }
            return w.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }
    }

    /** QA D141: a receipt for one confirmed payment. */
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, Locale locale) {
        return generateReceiptPdf(invoice, payment, String.valueOf(payment.getId()), nz(invoice.getAmountPaid()), locale);
    }

    /**
     * QA D228: {@code receiptNumber} is the readable number printed on the
     * receipt and {@code paidAsOfPayment} the invoice's amount paid right
     * after this payment, so a reprint shows the balance at that moment.
     */
    public byte[] generateReceiptPdf(BillingInvoice invoice, PaymentTransaction payment, String receiptNumber,
                                     BigDecimal paidAsOfPayment, Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (var doc = new PDDocument()) {
            var w = new Writer(doc);
            w.logo();
            w.text(BOLD, 16, MARGIN, t.get("pdf.receipt.title"));
            w.down(20);
            w.text(BODY, 11, MARGIN, t.get(HOSPITAL, hospitalName(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.number", safe(receiptNumber)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.invoice", safe(invoice.getInvoiceNumber())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(PATIENT, patientLine(invoice)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.date", t.fmt(payment.getPaymentDate())));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get("pdf.receipt.method", methodLabel(t, payment)));
            if (payment.getReferenceNumber() != null && !payment.getReferenceNumber().isBlank()) {
                w.down(15);
                w.text(BODY, 11, MARGIN, t.get("pdf.receipt.reference", payment.getReferenceNumber()));
            }
            w.down(28);
            w.text(BOLD, 14, MARGIN, t.get("pdf.receipt.amount", money(payment.getAmount())));
            // QA D228: the total when this money was received, not today's.
            BigDecimal total = payment.getInvoiceTotalAtReceipt() != null
                ? payment.getInvoiceTotalAtReceipt() : nz(invoice.getTotalAmount());
            BigDecimal paid = nz(paidAsOfPayment);
            w.down(24);
            w.text(BODY, 11, MARGIN, t.get(GRAND_TOTAL, money(total)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(AMOUNT_PAID, money(paid)));
            w.down(15);
            w.text(BODY, 11, MARGIN, t.get(BALANCE_DUE, money(total.subtract(paid).max(BigDecimal.ZERO))));
            return w.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate receipt PDF", e);
        }
    }

    /**
     * Whole francs with an ASCII-space thousands separator and the currency: "2 500 F CFA".
     * The franc CFA has no minor unit; the locale's own grouping character (a narrow
     * no-break space in French) is not a WinAnsi glyph, so it is not used.
     */
    static String money(BigDecimal amount) {
        BigDecimal v = nz(amount).setScale(0, RoundingMode.HALF_UP);
        String digits = v.abs().toPlainString();
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return (v.signum() < 0 ? "-" : "") + grouped + " F CFA";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal lineTotal(InvoiceItem item) {
        if (item.getTotalPrice() != null) return item.getTotalPrice();
        if (item.getUnitPrice() != null && item.getQuantity() != null) {
            return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        }
        return BigDecimal.ZERO;
    }

    private static String hospitalName(BillingInvoice invoice) {
        return invoice.getHospital() != null ? safe(invoice.getHospital().getName()) : "";
    }

    private static String patientLine(BillingInvoice invoice) {
        if (invoice.getPatient() == null) {
            return "";
        }
        String mrn = invoice.getHospital() != null
            ? invoice.getPatient().getMrnForHospital(invoice.getHospital().getId()) : null;
        return (safe(invoice.getPatient().getFirstName()) + " " + safe(invoice.getPatient().getLastName())
            + " " + safe(mrn)).trim();
    }

    private static String statusLabel(PdfLabels t, BillingInvoice invoice) {
        if (invoice.getStatus() == null) {
            return "";
        }
        String code = invoice.getStatus().name();
        return t.getOrCode("pdf.invoice.statusValue." + code, code);
    }

    private static String methodLabel(PdfLabels t, PaymentTransaction p) {
        if (p.getPaymentMethod() == null) {
            return "";
        }
        String code = p.getPaymentMethod().name();
        return t.getOrCode("pdf.payment.method." + code, code);
    }

    /** The line's own description; the category label only when the line has none. */
    private static String designation(PdfLabels t, InvoiceItem it) {
        String d = it.getItemDescription();
        if (d != null && !d.isBlank()) {
            return d;
        }
        return categoryLabel(t, it.getItemCategory());
    }

    /** The category's bundle label; the enum constant itself for a value the bundle does not know. */
    private static String categoryLabel(PdfLabels t, ItemCategory category) {
        if (category == null) {
            return "";
        }
        return t.getOrCode("pdf.invoice.category." + category.name(), category.name());
    }

    private static String safe(String v) { return v == null ? "" : v; }

    /** Standard-14 fonts carry WinAnsi only; a glyph outside it becomes '?', not an exception. */
    static String sanitize(PDFont font, String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            try {
                font.encode(String.valueOf(c));
                out.append(c);
            } catch (IllegalArgumentException | IOException e) {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static byte[] buildQrPng(String content) {
        try {
            var hints = Map.of(EncodeHintType.MARGIN, 1);
            var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220, hints);
            var img = MatrixToImageWriter.toBufferedImage(matrix);
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (com.google.zxing.WriterException | java.io.IOException e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }

    /** A cursor over pages: moves down, and opens a new page when the room runs out. */
    private static final class Writer {
        private static String truncate(String s, int n) {
            if (s == null) return "";
            return s.length() > n ? s.substring(0, n - 1) + "…" : s;
        }

        private final PDDocument doc;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void logo() throws IOException {
            try (var logoStream = PdfInvoiceService.class.getResourceAsStream("/branding/hospital-logo.png")) {
                if (logoStream != null) {
                    var logo = PDImageXObject.createFromByteArray(doc, logoStream.readAllBytes(), "logo");
                    cs.drawImage(logo, MARGIN, y - 60, 120, 40);
                }
            }
            y -= 80;
        }

        void qr(byte[] png) throws IOException {
            var qr = PDImageXObject.createFromByteArray(doc, png, "qr");
            cs.drawImage(qr, page.getMediaBox().getWidth() - MARGIN - 90, y - 10, 80, 80);
        }

        void down(float dy) {
            y -= dy;
        }

        void ensureRoom(float needed) throws IOException {
            if (y - needed < BOTTOM) {
                newPage();
            }
        }

        void text(PDFont font, float size, float x, String text) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(font, text));
            cs.endText();
        }

        void right(PDFont font, float size, String text) throws IOException {
            String clean = sanitize(font, text);
            float width = font.getStringWidth(clean) / 1000f * size;
            float x = page.getMediaBox().getWidth() - MARGIN - width;
            text(font, size, x, clean);
        }

        void row(PDFont font, String d, String c, String q, String u, String total) throws IOException {
            text(font, 10, MARGIN, truncate(d, 40));
            text(font, 10, MARGIN + 240, truncate(c, 16));
            text(font, 10, MARGIN + 330, safe(q));
            text(font, 10, MARGIN + 370, safe(u));
            text(font, 10, MARGIN + 450, safe(total));
        }

        void rule() throws IOException {
            cs.moveTo(MARGIN, y);
            cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y);
            cs.stroke();
        }

        byte[] finish() throws IOException {
            cs.close();
            cs = null;
            try (var baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        }
    }

    /** QA D974: a reason fits one printed line (the full text stays on the adjustment). */
    private static String shortReason(String reason) {
        String text = reason == null ? "" : reason.strip();
        return text.length() > 60 ? text.substring(0, 59) + "…" : text;
    }
}
