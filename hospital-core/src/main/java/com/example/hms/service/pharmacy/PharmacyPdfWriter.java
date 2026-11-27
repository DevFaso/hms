package com.example.hms.service.pharmacy;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The pharmacy's printed documents (QA D966 prescription copy, QA D967
 * controlled-drug register): PDFBox standard fonts, like the invoice and the
 * labels, with text wrapped to the page and tables split across pages. A
 * character outside WinAnsi prints as '?' rather than failing the document.
 *
 * <p>Public since V356: the clinical-rule sign-off pack prints through it too.
 */
public final class PharmacyPdfWriter implements AutoCloseable {

    public static final PDFont REGULAR = PDType1Font.HELVETICA;
    public static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final float MARGIN = 40f;
    private static final float LEADING = 1.35f;

    private final PDDocument doc = new PDDocument();
    private final PDRectangle size;
    private PDPageContentStream cs;
    private float y;
    /** Repeated at the top of every page a table continues on. */
    private float[] tableWidths;
    private String[] tableHeader;
    /** Optional, set by {@link #pageFurniture}: a header line and a « Page n / N » footer drawn at {@link #finish()}. */
    private String furnitureHeader;
    private String furniturePageWord;

    public PharmacyPdfWriter(boolean landscape) throws IOException {
        this.size = landscape ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
        newPage();
    }

    public float contentWidth() {
        return size.getWidth() - 2 * MARGIN;
    }

    private void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(size);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        y = size.getHeight() - MARGIN;
    }

    private void ensureRoom(float needed) throws IOException {
        if (y - needed < MARGIN) {
            newPage();
            if (tableHeader != null) {
                writeRow(tableWidths, tableHeader, BOLD, 8f);
            }
        }
    }

    /** The height a row of {@code cells} takes once each cell is wrapped to its column. */
    private float rowHeight(float[] widths, String[] cells, PDFont font, float fontSize) throws IOException {
        int lines = 1;
        for (int i = 0; i < cells.length; i++) {
            lines = Math.max(lines, wrap(font, fontSize, sanitize(font, cells[i]), widths[i] - 4f).size());
        }
        return lines * fontSize * LEADING + 2f;
    }

    /** One wrapped block of text, the full content width. */
    public void paragraph(PDFont font, float fontSize, String text) throws IOException {
        for (String line : wrap(font, fontSize, sanitize(font, text), contentWidth())) {
            ensureRoom(fontSize * LEADING);
            y -= fontSize * LEADING;
            show(font, fontSize, MARGIN, y, line);
        }
    }

    /** A paragraph aligned with the indented medicine fields, including wrapped lines. */
    public void indentedParagraph(PDFont font, float fontSize, String text) throws IOException {
        float indent = width(BOLD, fontSize, "   ");
        for (String line : wrap(font, fontSize, sanitize(font, text), contentWidth() - indent)) {
            ensureRoom(fontSize * LEADING);
            y -= fontSize * LEADING;
            show(font, fontSize, MARGIN + indent, y, line);
        }
    }

    /** "Label : value", the label in bold, the value wrapped after it. */
    public void field(String label, String value, float fontSize) throws IOException {
        String head = sanitize(BOLD, label) + " ";
        float headWidth = width(BOLD, fontSize, head);
        List<String> lines = wrap(REGULAR, fontSize, sanitize(REGULAR, value == null || value.isBlank() ? "-" : value),
                contentWidth() - headWidth);
        for (int i = 0; i < lines.size(); i++) {
            ensureRoom(fontSize * LEADING);
            y -= fontSize * LEADING;
            if (i == 0) {
                show(BOLD, fontSize, MARGIN, y, head);
            }
            show(REGULAR, fontSize, MARGIN + headWidth, y, lines.get(i));
        }
    }

    public void space(float points) {
        y -= points;
    }

    public void rule() throws IOException {
        ensureRoom(6f);
        y -= 4f;
        cs.moveTo(MARGIN, y);
        cs.lineTo(size.getWidth() - MARGIN, y);
        cs.stroke();
        y -= 2f;
    }

    /** Starts a table: the header is written now and repeated on each new page. */
    public void tableHeader(float[] widths, String[] cells) throws IOException {
        this.tableWidths = widths.clone();
        this.tableHeader = cells.clone();
        // Review of #975: the header wraps in narrow columns ("Date et heure",
        // "Prescripteur"); it needs its own wrapped height plus one row's, or
        // it is drawn below the margin at the foot of a page.
        float headerHeight = rowHeight(widths, cells, BOLD, 8f);
        if (y - headerHeight - 8f * LEADING - 2f < MARGIN) {
            newPage();
        }
        writeRow(widths, cells, BOLD, 8f);
    }

    public void tableRow(String[] cells) throws IOException {
        int lines = 1;
        List<List<String>> wrapped = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            List<String> cell = wrap(REGULAR, 8f, sanitize(REGULAR, cells[i]), tableWidths[i] - 4f);
            wrapped.add(cell);
            lines = Math.max(lines, cell.size());
        }
        ensureRoom(lines * 8f * LEADING + 2f);
        float x = MARGIN;
        float top = y;
        for (int i = 0; i < cells.length; i++) {
            float lineY = top;
            for (String line : wrapped.get(i)) {
                lineY -= 8f * LEADING;
                show(REGULAR, 8f, x + 2f, lineY, line);
            }
            x += tableWidths[i];
        }
        y = top - lines * 8f * LEADING - 2f;
    }

    void endTable() {
        tableHeader = null;
        tableWidths = null;
    }

    private void writeRow(float[] widths, String[] cells, PDFont font, float fontSize) throws IOException {
        int lines = 1;
        List<List<String>> wrapped = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            List<String> cell = wrap(font, fontSize, sanitize(font, cells[i]), widths[i] - 4f);
            wrapped.add(cell);
            lines = Math.max(lines, cell.size());
        }
        float x = MARGIN;
        float top = y;
        for (int i = 0; i < cells.length; i++) {
            float lineY = top;
            for (String line : wrapped.get(i)) {
                lineY -= fontSize * LEADING;
                show(font, fontSize, x + 2f, lineY, line);
            }
            x += widths[i];
        }
        y = top - lines * fontSize * LEADING - 2f;
        cs.moveTo(MARGIN, y);
        cs.lineTo(MARGIN + sum(widths), y);
        cs.stroke();
    }

    /**
     * D3502: every page of the document carries {@code header} (the hospital and the print date) at the top and
     * « {@code pageWord} n / N » at the foot, so loose signed pages can be put back together and checked. Drawn in
     * the margins, when the page count is known.
     */
    public void pageFurniture(String header, String pageWord) {
        this.furnitureHeader = header;
        this.furniturePageWord = pageWord;
    }

    private void drawFurniture() throws IOException {
        int pages = doc.getNumberOfPages();
        for (int i = 0; i < pages; i++) {
            try (PDPageContentStream out = new PDPageContentStream(doc, doc.getPage(i),
                    PDPageContentStream.AppendMode.APPEND, true)) {
                String head = sanitize(REGULAR, furnitureHeader);
                String foot = sanitize(REGULAR, furniturePageWord + " " + (i + 1) + " / " + pages);
                out.beginText();
                out.setFont(REGULAR, 8f);
                out.newLineAtOffset(MARGIN, size.getHeight() - 24f);
                out.showText(head);
                out.endText();
                out.beginText();
                out.setFont(REGULAR, 8f);
                out.newLineAtOffset(size.getWidth() - MARGIN - width(REGULAR, 8f, foot), 22f);
                out.showText(foot);
                out.endText();
            }
        }
    }

    public byte[] finish() throws IOException {
        cs.close();
        cs = null;
        if (furnitureHeader != null) {
            drawFurniture();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        if (cs != null) {
            cs.close();
        }
        doc.close();
    }

    private void show(PDFont font, float fontSize, float x, float atY, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, atY);
        cs.showText(text);
        cs.endText();
    }

    private static float sum(float[] widths) {
        float total = 0;
        for (float w : widths) {
            total += w;
        }
        return total;
    }

    private static float width(PDFont font, float fontSize, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    /** Word wrap to {@code maxWidth}; a word longer than the line is cut. */
    static List<String> wrap(PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, fontSize, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width(font, fontSize, rest) > maxWidth && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(font, fontSize, rest.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }

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
}
