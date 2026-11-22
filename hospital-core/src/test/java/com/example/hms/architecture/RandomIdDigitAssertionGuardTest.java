package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-21 CI flakes: InvoiceRefundServiceTest asserted {@code doesNotContain("500")} and
 * PharmacyPaymentVoiderTest {@code !d.contains("200")} on an audit text that names its rows by random
 * UUIDs; a UUID holding that digit run failed the build. A negative assertion of a short digit literal
 * (up to 8 digits: a longer run in 32 random hex digits is below one in a billion) must read the text
 * through {@link com.example.hms.testsupport.RandomIds#withoutRandomIds(Object)} in the same statement,
 * even where the text has no id today: the next change to the description should not make the test a coin.
 */
class RandomIdDigitAssertionGuardTest {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern DOES_NOT_CONTAIN = Pattern.compile("\\.doesNotContain\\(");
    private static final Pattern SHORT_DIGITS = Pattern.compile("\"\\d{1,8}+\"");
    /** {@code !text.contains("500")}, {@code !String.valueOf(x).contains("900")}: up to the next ; ! & or |. */
    private static final Pattern NEGATED_CONTAINS = Pattern.compile("!\\s*+[^;!&|]*?\\.contains\\(\"\\d{1,8}+\"\\)");
    private static final String WRAPPER = "withoutRandomIds(";
    /** This file names the shapes in its own string literals. */
    private static final String SELF = "RandomIdDigitAssertionGuardTest.java";

    @Test
    void everyNegativeShortDigitAssertionReadsTheTextWithoutItsRandomIds() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(TESTS)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.endsWith(SELF)).sorted().toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (int line : offendingLines(code)) {
                    offenders.add(TESTS.relativize(file).toString().replace('\\', '/') + ":" + line);
                }
            }
        }
        assertThat(offenders)
            .as("a doesNotContain(\"<digits>\") / !x.contains(\"<digits>\") on a text that may name a random UUID fails"
                + " whenever the UUID holds those digits: assert on RandomIds.withoutRandomIds(text)" + SourceScan.MANDATE)
            .isEmpty();
    }

    static List<Integer> offendingLines(String code) {
        List<Integer> lines = new ArrayList<>();
        int[] starts = SourceScan.statementStarts(code);
        Matcher call = DOES_NOT_CONTAIN.matcher(code);
        while (call.find()) {
            int open = call.end() - 1;
            int close = SourceScan.closingParen(code, open);
            if (SHORT_DIGITS.matcher(code.substring(open, Math.min(close + 1, code.length()))).find()
                && !code.substring(SourceScan.statementStartBefore(starts, call.start()), close).contains(WRAPPER)) {
                lines.add(SourceScan.lineOf(code, call.start()));
            }
        }
        Matcher negated = NEGATED_CONTAINS.matcher(code);
        while (negated.find()) {
            if (!negated.group().contains(WRAPPER)) {
                lines.add(SourceScan.lineOf(code, negated.start()));
            }
        }
        return lines;
    }

    @Test
    void theScanSeesTheShape() {
        assertThat(offendingLines("assertThat(e.getEventDescription()).doesNotContain(\"500\");")).hasSize(1);
        assertThat(offendingLines("assertThat(d)\n  .doesNotContain(\"Consultation\", \"2000\");")).hasSize(1);
        assertThat(offendingLines("argThat(d -> d.contains(id) && !d.contains(\"200\"))")).hasSize(1);
        assertThat(offendingLines("argThat(e -> !String.valueOf(e.getDetails()).contains(\"900\"))")).hasSize(1);

        assertThat(offendingLines("assertThat(withoutRandomIds(e.getEventDescription())).doesNotContain(\"500\");")).isEmpty();
        assertThat(offendingLines("argThat(d -> !withoutRandomIds(d).contains(\"200\"))")).isEmpty();
        assertThat(offendingLines("assertThat(d).doesNotContain(\"Trop-perçu\").doesNotContain(\"7.2\");")).isEmpty();
        assertThat(offendingLines("assertThat(d).doesNotContain(\"22670707070\");")).as("11 digits: not a coin").isEmpty();
        assertThat(offendingLines("assertThat(d).doesNotContainPattern(\"\\\\b500\\\\b\");")).isEmpty();
        assertThat(offendingLines("assertThat(d).contains(\"500\");")).isEmpty();
        assertThat(offendingLines("if (x != null && d.contains(\"500\")) { }")).isEmpty();
    }
}
