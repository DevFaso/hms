package com.example.hms.service;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PdfInvoiceService {

    public byte[] generateInvoicePdf(BillingInvoice invoice,
                                     List<InvoiceItem> items,
                                     Locale locale) {
        try (var doc = new PDDocument()) {
            var page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                float margin = 50f;
                float y = page.getMediaBox().getHeight() - margin;

                // Logo (from classpath)
                var logoStream = getClass().getResourceAsStream("/branding/hospital-logo.png");
                if (logoStream != null) {
                    var logo = PDImageXObject.createFromByteArray(doc, logoStream.readAllBytes(), "logo");
                    cs.drawImage(logo, margin, y - 60, 120, 40);
                }

                // Header text
                y -= 80;
                writeText(cs, 16, margin, y, "INVOICE " + invoice.getInvoiceNumber());
                y -= 20;
                writeText(cs, 11, margin, y, "Date: " + invoice.getInvoiceDate() + "    Due: " + invoice.getDueDate());
                y -= 15;
                writeText(cs, 11, margin, y, "Patient: " +
                    safe(invoice.getPatient().getFirstName()) + " " +
                    safe(invoice.getPatient().getLastName()) + " " +
                    safe(invoice.getPatient().getMrnForHospital(invoice.getHospital().getId()))
                );
                y -= 15;
                writeText(cs, 11, margin, y, "Hospital: " + safe(invoice.getHospital().getName()));

                // QR (invoice lookup URL or deep link)
                y -= 90;
                var qrPng = buildQrPng("hms://invoice/" + invoice.getId());
                var qr = PDImageXObject.createFromByteArray(doc, qrPng, "qr");
                cs.drawImage(qr, page.getMediaBox().getWidth() - margin - 90, y + 10, 80, 80);

                // Table header
                y -= 10;
                drawLine(cs, margin, y, page.getMediaBox().getWidth() - margin, y);
                y -= 14;
                writeRow(cs, margin, y, "Description", "Category", "Qty", "Unit", "Total");
                y -= 10;
                drawLine(cs, margin, y, page.getMediaBox().getWidth() - margin, y);

                // Table rows
                for (var it : items) {
                    y -= 16;
                    writeRow(cs, margin, y,
                        it.getItemDescription(),
                        it.getItemCategory().name(),
                        String.valueOf(it.getQuantity()),
                        it.getUnitPrice().toString(),
                        it.getTotalPrice().toString());
                    if (y < 100) break;
                }

                // Totals
                y -= 20;
                drawLine(cs, margin, y, page.getMediaBox().getWidth() - margin, y);
                y -= 18;
                writeTextRight(cs, 12, page, margin, y, "Grand Total: " + invoice.getTotalAmount());
            }
            try (var baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate invoice PDF", e);
        }
    }

    private static void writeText(PDPageContentStream cs, int size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static void writeTextRight(PDPageContentStream cs, int size, PDPage page, float margin, float y, String text) throws IOException {
        var width = page.getMediaBox().getWidth();
        var textWidth = size * text.length() * 0.5f;
        writeText(cs, size, width - margin - textWidth, y, text);
    }

    private static void writeRow(PDPageContentStream cs, float x, float y,
                                 String d, String c, String q, String u, String t) throws IOException {
        writeText(cs, 10, x, y, truncate(d, 42));
        writeText(cs, 10, x + 260, y, truncate(c, 16));
        writeText(cs, 10, x + 360, y, q);
        writeText(cs, 10, x + 400, y, u);
        writeText(cs, 10, x + 470, y, t);
    }

    private static void drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1); cs.lineTo(x2, y2); cs.stroke();
    }

    private static String safe(String v) { return v == null ? "" : v; }
    private static String truncate(String s, int n) { return s != null && s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    private static byte[] buildQrPng(String content) {
        try {
            var hints = Map.of(EncodeHintType.MARGIN, 1);
            var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220, hints);
            var img = MatrixToImageWriter.toBufferedImage(matrix);
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }
}
