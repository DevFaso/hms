package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1384 (round 32, the D1350 class): {@code GET /admissions?status=BOGUS}
 * answered "No enum constant com.example.hms.enums.AdmissionStatus.BOGUS" -
 * raw Java text, in every language, no {@code fieldErrors} - because the
 * filter was read with an uncaught {@code AdmissionStatus.valueOf(status.toUpperCase())}.
 * The same shape sat in the order-set type filter and the referral lists.
 *
 * <p>Rule: a text read as an enum constant ({@code X.valueOf(text.toUpperCase(...))})
 * is inside a {@code try} whose {@code catch (IllegalArgumentException ...)}
 * turns it into a refusal (a field-named one for a query parameter:
 * {@code EnumQueryParam.refusal}), or the file is listed below with why the
 * text cannot be unknown there.
 */
class RawEnumValueOfGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** {@code Type.valueOf(}: its argument is then read to its closing parenthesis. */
    private static final Pattern VALUE_OF = Pattern.compile("\\b([A-Z][A-Za-z0-9]*+)\\.valueOf\\(");

    /** Wrapper types whose {@code valueOf} is not an enum lookup. */
    private static final Set<String> NOT_AN_ENUM = Set.of("String", "Integer", "Long", "Boolean", "BigDecimal",
        "Double", "Float", "Short", "Byte", "Character");

    /** A catch that takes the unknown-constant failure (LabQcEventServiceImpl catches Exception). */
    private static final Pattern CATCHES =
        Pattern.compile("catch \\((?:IllegalArgumentException|RuntimeException|Exception)\\b");

    /** How far before the call its {@code try} may open, and after it its catch may stand. */
    private static final int TRY_WINDOW = 400;
    private static final int CATCH_WINDOW = 600;

    /** Files whose upper-cased enum read cannot meet an unknown value, and why. */
    private static final Map<String, String> ALLOWED = Map.of(
        "mapper/LabOrderMapper.java",
        "status already held to the enum by LabOrderRequestDTO's @Pattern (QA D1370) before the mapper runs",
        "enums/DataDomain.java",
        "parses a STORED consent scope, held to the enum by DataDomainCsvValidator on every write; documented to"
            + " throw IllegalArgumentException");

    @Test
    void anUpperCasedTextIsNeverReadAsAnEnumWithoutACatch() throws IOException {
        Map<String, String> found = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                scanned++;
                Matcher m = VALUE_OF.matcher(src);
                while (m.find()) {
                    if (readsUpperCasedText(src, m) && !caught(src, m.start()) && !ALLOWED.containsKey(rel)) {
                        found.put(rel + ":" + SourceScan.lineOf(src, m.start()), m.group(1)
                            + ".valueOf(...toUpperCase()) with no catch: an unknown value answers raw Java text"
                            + " (QA D1384)");
                    }
                }
            }
        }
        assertThat(scanned).as("the scan read the main sources").isGreaterThan(1000);
        assertThat(found)
            .as("an enum read from text with no IllegalArgumentException catch%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theScanSeesTheShapeItGuards() {
        String bare = "AdmissionStatus admissionStatus = AdmissionStatus.valueOf(status.toUpperCase());";
        String trimmed = "x = ReferralStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));";
        for (String shape : new String[] {bare, trimmed}) {
            Matcher m = VALUE_OF.matcher(shape);
            assertThat(m.find()).isTrue();
            assertThat(readsUpperCasedText(shape, m)).as(shape).isTrue();
            assertThat(caught(shape, m.start())).as(shape).isFalse();
        }
        String wrapper = "y = String.valueOf(s.toUpperCase());";
        Matcher w = VALUE_OF.matcher(wrapper);
        assertThat(w.find()).isTrue();
        assertThat(readsUpperCasedText(wrapper, w)).isFalse();
        String guarded = "try {\n  return X.valueOf(s.trim().toUpperCase(Locale.ROOT));\n}"
            + " catch (IllegalArgumentException ex) {\n  throw EnumQueryParam.refusal(\"status\");\n}";
        Matcher m = VALUE_OF.matcher(guarded);
        assertThat(m.find()).isTrue();
        assertThat(caught(guarded, m.start())).isTrue();
    }

    @Test
    void theAllowlistNamesRealFiles() {
        for (String rel : ALLOWED.keySet()) {
            assertThat(MAIN.resolve(rel)).as("stale allowlist entry " + rel).exists();
        }
    }

    /** An enum's {@code valueOf} whose argument is an upper-cased text. */
    private static boolean readsUpperCasedText(String src, Matcher m) {
        if (NOT_AN_ENUM.contains(m.group(1))) {
            return false;
        }
        int open = m.end() - 1;
        int close = SourceScan.closingParen(src, open);
        return close > open && src.substring(open, close).contains(".toUpperCase(");
    }

    /** A {@code try} opened shortly before the call, and its IllegalArgumentException catch shortly after. */
    private static boolean caught(String src, int at) {
        int tryAt = src.lastIndexOf("try {", at);
        if (tryAt < 0 || at - tryAt > TRY_WINDOW) {
            return false;
        }
        Matcher c = CATCHES.matcher(src).region(at, src.length());
        int catchAt = c.find() ? c.start() : -1;
        return catchAt >= 0 && catchAt - at <= CATCH_WINDOW
            && src.lastIndexOf("try {", catchAt) == tryAt;
    }
}
