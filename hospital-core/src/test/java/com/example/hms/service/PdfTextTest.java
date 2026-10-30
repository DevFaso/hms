package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

/** QA D1091 / D1036: printed text wraps or shrinks by its width; a word is never cut. */
class PdfTextTest {

    private static final PDFont FONT = PDType1Font.HELVETICA;

    @Test
    void aLongReasonWrapsWithEveryWordWhole() throws IOException {
        String reason = "Remise accordée au titre du programme national de gratuité des soins (demo-r18-adj-01)";
        List<String> lines = PdfText.wrap(FONT, 10, reason, 150f);

        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(String.join(" ", lines)).isEqualTo(reason);
        for (String line : lines) {
            assertThat(PdfText.width(FONT, 10, line)).isLessThanOrEqualTo(150f);
        }
    }

    @Test
    void aNameShrinksToFitBeforeAnythingIsCut() throws IOException {
        String name = "Pélagie Wendkouni Kiemdé";
        float size = PdfText.fitSize(FONT, name, 100f, 11f, 6f);
        assertThat(size).isLessThan(11f);
        assertThat(PdfText.width(FONT, size, name)).isLessThanOrEqualTo(100f);
        assertThat(PdfText.wrapAtMost(FONT, size, name, 100f, 1)).containsExactly(name);
    }

    @Test
    void whatCannotFitEndsAtAWordBoundaryWithAnEllipsis() throws IOException {
        List<String> lines = PdfText.wrapAtMost(FONT, 10, "Abdoul Aziz Wendlassida Nikiéma Ouédraogo", 90f, 1);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).endsWith("…");
        String kept = lines.get(0).substring(0, lines.get(0).length() - 1);
        assertThat(kept).isEqualTo("Abdoul Aziz Wendlassida Nikiéma Ouédraogo".substring(0, kept.length()));
        assertThat("Abdoul Aziz Wendlassida Nikiéma Ouédraogo".charAt(kept.length())).isEqualTo(' ');
    }

    @Test
    void blankTextIsOneEmptyLine() throws IOException {
        assertThat(PdfText.wrap(FONT, 10, null, 100f)).containsExactly("");
        assertThat(PdfText.wrap(FONT, 10, "   ", 100f)).containsExactly("");
    }
}
