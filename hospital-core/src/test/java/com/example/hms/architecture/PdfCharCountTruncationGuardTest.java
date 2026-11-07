package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1091 / D1036 (class guard): printed text is laid out by its width
 * ({@code PdfText.wrap}, {@code fitSize}, {@code wrapAtMost}), never cut at a
 * character count. The invoice cut the mandatory adjustment reason at 60
 * characters, mid-word ("…programme (demo-r1…"), its table cut every
 * description at 40 and every category at 16, and the wristband cut the
 * patient's name at 28, so a middle name was half printed.
 *
 * <p>Every main class that draws with PDFBox is scanned for the two shapes of
 * the cut: {@code s.substring(0, n) + "…"} and a {@code truncate(text, 28)}
 * call with a literal count. A hash prefix ({@code fingerprint.substring(0, 16)})
 * carries no ellipsis and is not a cut.
 */
class PdfCharCountTruncationGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern ELLIPSIS_CUT = Pattern.compile(
        "\\.substring\\(\\s*0\\s*,[^;]*?\\)\\s*\\+\\s*\"(?:…|\\\\u2026|\\.\\.\\.)\"");

    private static final Pattern COUNTED_TRUNCATE = Pattern.compile("\\btruncate\\([^;]*?,\\s*\\d+\\s*\\)");

    @Test
    void noPdfRendererCutsTextAtACharacterCount() throws IOException {
        List<String> sites = new ArrayList<>();
        int renderers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                if (!src.contains("org.apache.pdfbox")) {
                    continue;
                }
                renderers++;
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                for (Pattern shape : List.of(ELLIPSIS_CUT, COUNTED_TRUNCATE)) {
                    Matcher m = shape.matcher(src);
                    while (m.find()) {
                        sites.add(rel + ":" + SourceScan.lineOf(src, m.start()) + " " + m.group());
                    }
                }
            }
        }
        assertThat(renderers).as("PDF renderers found").isGreaterThanOrEqualTo(5);
        assertThat(sites)
            .as("printed text cut at a character count, mid-word (QA D1091): wrap or fit it with PdfText; "
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theScanSeesBothShapes() {
        assertThat(ELLIPSIS_CUT.matcher("return text.length() > 60 ? text.substring(0, 59) + \"…\" : text;").find())
            .isTrue();
        assertThat(COUNTED_TRUNCATE.matcher("writeText(cs, 11, 8, y, truncate(name, 28));").find()).isTrue();
        assertThat(ELLIPSIS_CUT.matcher("fingerprint.substring(0, 16))").find()).isFalse();
        assertThat(COUNTED_TRUNCATE.matcher("PdfText.wrapAtMost(font, size, text, width, 1)").find()).isFalse();
    }
}
