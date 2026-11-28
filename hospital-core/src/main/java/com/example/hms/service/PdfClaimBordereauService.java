package com.example.hms.service;

import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDTO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Insurer claims plan PR 2 (US-8, QA D3983): the bordereau as a document for the payer: the hospital, the payer, the
 * period and the number; one row per claimed act with the patient, the policy, the invoice, the act, what was
 * billed, the patient's part and the payer's part, the authorisation number and the documents attached; the totals
 * and the signature block. One generic layout (owner decision Q3), French by default. A4 landscape; every cell wraps
 * by its printed width and a row that does not fit starts a new page with the column heads repeated.
 */
@Service
@RequiredArgsConstructor
public class PdfClaimBordereauService {

    private static final float MARGIN = 36f;
    private static final float BOTTOM = 48f;
    private static final float SIZE = 8f;
    private static final float LINE = 10f;
    private static final PDFont BODY = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;

    /** Column widths in points; they fill the 770 points between the margins of an A4 landscape page. */
    private static final float[] WIDTHS = {150f, 78f, 190f, 26f, 70f, 70f, 70f, 116f};
    /** The amount columns, printed against their right edge. */
    private static final boolean[] RIGHT = {false, false, false, true, true, true, true, false};
    private static final float GAP = 6f;

    private final MessageSource messageSource;

    /** The bordereau's PDF. {@code batch} carries its lines; {@code hospitalName} heads the page. */
    public byte[] render(InsurerClaimBatchDTO batch, String hospitalName, Locale locale) {
        PdfLabels t = PdfLabels.of(messageSource, locale);
        try (PDDocument doc = new PDDocument()) {
            Sheet sheet = new Sheet(doc, heads(t));
            header(sheet, t, batch, hospitalName);
            sheet.tableHead();
            List<InsurerClaimLineDTO> lines = batch.getLines() != null ? batch.getLines() : List.of();
            for (InsurerClaimLineDTO line : lines) {
                sheet.row(BODY, cells(t, line));
                String documents = line.getAttachedDocuments();
                if (documents != null && !documents.isBlank()) {
                    sheet.note(t.get("pdf.bordereau.documents", documents.strip()));
                }
            }
            sheet.rule();
            sheet.row(BOLD, new String[] {t.get("pdf.bordereau.total", lines.size()), "", "", "",
                money(batch.getGrossTotal(), t), money(batch.getPatientTotal(), t),
                money(batch.getClaimedTotal(), t), ""});
            sheet.signatures(t.get("pdf.bordereau.hospitalSignature"), t.get("pdf.bordereau.payerReceipt"));
            return sheet.finish();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String[] heads(PdfLabels t) {
        return new String[] {t.get("pdf.bordereau.col.patient"), t.get("pdf.bordereau.col.invoice"),
            t.get("pdf.bordereau.col.act"), t.get("pdf.bordereau.col.quantity"), t.get("pdf.bordereau.col.gross"),
            t.get("pdf.bordereau.col.patientShare"), t.get("pdf.bordereau.col.payerShare"),
            t.get("pdf.bordereau.col.authorisation")};
    }

    private static void header(Sheet sheet, PdfLabels t, InsurerClaimBatchDTO batch, String hospitalName)
            throws IOException {
        sheet.text(BOLD, 14, MARGIN, t.get("pdf.bordereau.title"));
        sheet.down(18);
        sheet.text(BODY, 10, MARGIN, t.get("pdf.bordereau.hospital", safe(hospitalName)));
        sheet.down(13);
        sheet.text(BODY, 10, MARGIN, t.get("pdf.bordereau.payer", safe(batch.getPayerName())));
        sheet.down(13);
        sheet.text(BODY, 10, MARGIN, t.get("pdf.bordereau.period", t.fmt(batch.getPeriodFrom()),
            t.fmt(batch.getPeriodTo())));
        sheet.down(13);
        if (batch.getNumber() != null) {
            sheet.text(BODY, 10, MARGIN, t.get("pdf.bordereau.number", batch.getNumber(),
                t.fmt(batch.getSubmittedOn())));
        } else {
            sheet.text(BOLD, 10, MARGIN, t.get("pdf.bordereau.draft"));
        }
        sheet.down(18);
    }

    private static String[] cells(PdfLabels t, InsurerClaimLineDTO line) {
        List<String> who = new ArrayList<>();
        who.add(safe(line.getPatientName()));
        if (line.getPolicyNumber() != null && !line.getPolicyNumber().isBlank()) {
            who.add(t.get("pdf.invoice.policyNumber", line.getPolicyNumber().strip()));
        }
        if (line.getSubscriberName() != null && !line.getSubscriberName().isBlank()) {
            who.add(t.get("pdf.invoice.subscriber", line.getSubscriberName().strip()));
        }
        String dates = line.getDischargeDate() != null
            ? t.get("pdf.bordereau.discharged", t.fmt(line.getDischargeDate())) : t.fmt(line.getServiceDate());
        String act = line.getActCode() != null && !line.getActCode().isBlank()
            ? line.getActCode() + " - " + safe(line.getActLabel()) : safe(line.getActLabel());
        return new String[] {String.join(" / ", who), safe(line.getInvoiceNumber()) + " / " + dates, act,
            line.getQuantity() != null ? String.valueOf(line.getQuantity()) : "",
            money(line.getGrossAmount(), t), money(line.getPatientAmount(), t), money(line.getClaimedAmount(), t),
            safe(line.getPreAuthorisationNumber())};
    }

    private static String money(BigDecimal amount, PdfLabels t) {
        return PdfInvoiceService.money(amount, t.locale());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** A cursor over landscape pages: a table whose rows wrap and whose head repeats on each page. */
    private static final class Sheet {
        private final PDDocument doc;
        private final String[] heads;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;

        Sheet(PDDocument doc, String[] heads) throws IOException {
            this.doc = doc;
            this.heads = heads;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void down(float dy) {
            y -= dy;
        }

        void text(PDFont font, float size, float x, String text) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(PdfInvoiceService.sanitize(font, text));
            cs.endText();
        }

        void tableHead() throws IOException {
            row(BOLD, heads, false);
            rule();
        }

        void row(PDFont font, String[] cells) throws IOException {
            row(font, cells, true);
        }

        private void row(PDFont font, String[] cells, boolean repeatHead) throws IOException {
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int i = 0; i < WIDTHS.length; i++) {
                List<String> lines = PdfText.wrap(font, SIZE, PdfInvoiceService.sanitize(font, cells[i]),
                    WIDTHS[i] - GAP);
                wrapped.add(lines);
                height = Math.max(height, lines.size());
            }
            if (y - height * LINE < BOTTOM) {
                newPage();
                if (repeatHead) {
                    tableHead();
                }
            }
            float x = MARGIN;
            for (int i = 0; i < WIDTHS.length; i++) {
                float lineY = y;
                for (String line : wrapped.get(i)) {
                    float at = RIGHT[i] ? x + WIDTHS[i] - GAP - PdfText.width(font, SIZE, line) : x;
                    cs.beginText();
                    cs.setFont(font, SIZE);
                    cs.newLineAtOffset(at, lineY);
                    cs.showText(line);
                    cs.endText();
                    lineY -= LINE;
                }
                x += WIDTHS[i];
            }
            y -= height * LINE + 3f;
        }

        /** A smaller line under a row, across the table: the documents attached to the act. */
        void note(String text) throws IOException {
            float width = page.getMediaBox().getWidth() - 2 * MARGIN - 12f;
            for (String line : PdfText.wrap(BODY, 7f, PdfInvoiceService.sanitize(BODY, text), width)) {
                if (y - LINE < BOTTOM) {
                    newPage();
                    tableHead();
                }
                text(BODY, 7f, MARGIN + 12f, line);
                y -= 9f;
            }
            y -= 2f;
        }

        void rule() throws IOException {
            cs.moveTo(MARGIN, y + 6f);
            cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y + 6f);
            cs.stroke();
            y -= 4f;
        }

        void signatures(String left, String right) throws IOException {
            if (y - 70f < BOTTOM) {
                newPage();
            }
            y -= 36f;
            text(BODY, 10, MARGIN, left);
            text(BODY, 10, page.getMediaBox().getWidth() / 2f, right);
        }

        byte[] finish() throws IOException {
            cs.close();
            cs = null;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }
}
