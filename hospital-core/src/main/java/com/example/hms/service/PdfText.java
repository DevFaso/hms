package com.example.hms.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * QA D1091 / D1036: printed text is laid out by its WIDTH, never cut at a
 * character count. The invoice cut the mandatory adjustment reason at 60
 * characters ("…programme (demo-r1…"), the wristband cut a patient's name at
 * 28, so a middle name was half printed. Text either wraps onto further
 * lines or, on a fixed-size label, shrinks to fit; when it still cannot fit,
 * it is shortened at a word boundary with an ellipsis. {@code
 * PdfCharCountTruncationGuardTest} keeps the character-count cut out of every
 * PDF renderer.
 */
public final class PdfText {

    private static final String ELLIPSIS = "…";

    private PdfText() {
    }

    /** The printed width of {@code text} in points. */
    public static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /**
     * {@code text} word-wrapped to {@code maxWidth}: every word is printed
     * whole, on as many lines as it takes; only a single word wider than the
     * line itself is broken. Never empty: blank text is one empty line.
     */
    public static List<String> wrap(PDFont font, float size, String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String clean = text == null ? "" : text.strip();
        for (String word : clean.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, size, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(breakOverlongWord(font, size, word, maxWidth, lines));
            }
        }
        lines.add(line.toString());
        return lines;
    }

    /**
     * A single word wider than the line: its full-width pieces are added to
     * {@code lines}, and the remainder (which fits) is returned to start the
     * next line.
     */
    private static String breakOverlongWord(PDFont font, float size, String word, float maxWidth, List<String> lines)
            throws IOException {
        String rest = word;
        while (rest.length() > 1 && width(font, size, rest) > maxWidth) {
            int cut = rest.length() - 1;
            while (cut > 1 && width(font, size, rest.substring(0, cut)) > maxWidth) {
                cut--;
            }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        return rest;
    }

    /**
     * The largest size from {@code maxSize} down to {@code minSize} (half-point
     * steps) at which {@code text} fits {@code maxWidth} on one line, or
     * {@code minSize} when it fits at none.
     */
    public static float fitSize(PDFont font, String text, float maxWidth, float maxSize, float minSize)
            throws IOException {
        String clean = text == null ? "" : text;
        for (float size = maxSize; size > minSize; size -= 0.5f) {
            if (width(font, size, clean) <= maxWidth) {
                return size;
            }
        }
        return minSize;
    }

    /**
     * At most {@code maxLines} lines of {@code text} wrapped to {@code maxWidth};
     * when the text needs more, the last line ends at a word boundary with an
     * ellipsis that fits. A fixed-size label uses it after {@link #fitSize}.
     */
    public static List<String> wrapAtMost(PDFont font, float size, String text, float maxWidth, int maxLines)
            throws IOException {
        List<String> lines = wrap(font, size, text, maxWidth);
        if (lines.size() <= maxLines) {
            return lines;
        }
        List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
        String last = kept.get(maxLines - 1);
        while (true) {
            String candidate = last + ELLIPSIS;
            if (width(font, size, candidate) <= maxWidth || last.isEmpty()) {
                kept.set(maxLines - 1, candidate);
                return kept;
            }
            int space = last.lastIndexOf(' ');
            last = space > 0 ? last.substring(0, space) : last.substring(0, last.length() - 1);
        }
    }
}
