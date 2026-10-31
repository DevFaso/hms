package com.example.hms.i18n;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1079 (and D1051, D1052) guard, server side: a French label glued to its
 * colon ("Glycémie à jeun: 5,6 g/L", "Date de naissance:").
 *
 * <p>French typography writes a no-break space before a colon
 * ("Statut : Confirmé"). The portal half of this guard is
 * {@code hospital-portal/scripts/lib/glued-colon.test.mjs}; this half reads
 * what the server composes for display:
 *
 * <ul>
 *   <li>every French bundle value ({@code i18n/*_fr.properties}): no word
 *       glued to a colon ({@code {0}:{1}} between two placeholders, a URL or
 *       a time is not a label). The house idiom is a key that carries the
 *       colon, e.g. {@code pharmacy.register.labelValue={0} : {1}};</li>
 *   <li>a label joined to its value by {@code .append(": ")} anywhere in
 *       {@code src/main/java}: the colon is the language's, so it comes from a
 *       key, not from the code;</li>
 *   <li>a French string literal (one holding a French accented letter) with a
 *       word glued to a colon.</li>
 * </ul>
 *
 * <p>Only French requires the space: English bundle values, English audit
 * descriptions, log lines and FHIR narrative write "Status:" correctly and are
 * not read (the English-literal class itself is {@link StoredTextProseGuardTest}'s).
 * Each allowance names a site, its count and why.
 */
@DisplayName("French text composed by the server never glues a colon to its label")
class FrenchColonTypographyGuardTest {

    private static final Path BUNDLES = Paths.get("src/main/resources/i18n");
    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /**
     * A word, a closing bracket or a placeholder ({@code {0}:}) glued to a colon that ends a
     * label: not {@code a:b}, {@code https://}, {@code 10:30}.
     */
    private static final Pattern GLUED_LABEL = Pattern.compile("[\\p{L})}\\].'\\u2019]:(?![/\\p{L}\\p{N}])");
    /** {@code {0}:{1}} (an address or a ratio between two placeholders) is not a label. */
    private static final Pattern BETWEEN_PLACEHOLDERS = Pattern.compile("\\}:\\{");
    private static final Pattern APPEND_COLON = Pattern.compile("\\.append\\(\\s*\": \"\\s*\\)");
    private static final Pattern FRENCH_LETTER = Pattern.compile("[àâçéèêëîïôûùüÿœÀÂÇÉÈÊËÎÏÔÛÙÜŸŒ]");

    /** file::rule -> count, reason. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "fhir/mapper/DiagnosticReportFhirMapper.java::append", 1,
        "Not French display text: the FHIR imaging narrative is English interop text "
            + "(\"Technique: …\", \"Findings: …\"), never rendered by the portal");

    static boolean gluedFrenchLabel(String text) {
        return GLUED_LABEL.matcher(BETWEEN_PLACEHOLDERS.matcher(text).replaceAll("} {")).find();
    }

    @Test
    @DisplayName("no French bundle value glues a colon to the word before it")
    void frenchBundlesWriteASpaceBeforeTheColon() throws IOException {
        List<String> glued = new ArrayList<>();
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("_fr.properties")).sorted().toList()) {
                Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                for (String key : new TreeMap<>(props).keySet().stream().map(String::valueOf).toList()) {
                    String value = props.getProperty(key);
                    if (gluedFrenchLabel(value)) {
                        glued.add(file.getFileName() + ": " + key + "=" + value);
                    }
                }
            }
        }
        assertThat(glued).as("French writes \"Statut : x\" (a space before the colon). " + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("no server code joins a label to its value with a typed colon")
    void serverLabelsTakeTheColonFromTheLanguage() throws IOException {
        List<String> sites = new ArrayList<>();
        List<String> where = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher append = APPEND_COLON.matcher(source);
                while (append.find()) {
                    sites.add(rel + "::append");
                    where.add(rel + ":" + SourceScan.lineOf(source, append.start()) + " .append(\": \")");
                }
                for (int i = 0; i < source.length(); i++) {
                    if (source.charAt(i) != '"') {
                        continue;
                    }
                    int end = SourceScan.endOfLiteralOrComment(source, i);
                    String literal = source.substring(i + 1, Math.min(end, source.length()));
                    if (FRENCH_LETTER.matcher(literal).find() && gluedFrenchLabel(literal)) {
                        sites.add(rel + "::french-literal");
                        where.add(rel + ":" + SourceScan.lineOf(source, i) + " \"" + literal + "\"");
                    }
                    i = Math.max(i, end);
                }
            }
        }
        Map<String, Integer> actual = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("A French label glued to its colon: resolve a key that carries the colon "
                + "(e.g. pharmacy.register.labelValue={0} : {1}). Sites: " + where + " " + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).isEmpty();
    }

    @Test
    @DisplayName("the scan sees the shapes the defect had, and nothing else")
    void scanShapes() {
        assertThat(gluedFrenchLabel("Date de naissance:")).isTrue();
        assertThat(gluedFrenchLabel("Glycémie à jeun: {0}")).isTrue();
        assertThat(gluedFrenchLabel("Orientation à la sortie: Domicile")).isTrue();
        assertThat(gluedFrenchLabel("{0}: {1}")).isTrue();
        assertThat(gluedFrenchLabel("Statut : {0}")).isFalse();
        assertThat(gluedFrenchLabel("Statut : {0}")).isFalse();
        assertThat(gluedFrenchLabel("Transport vers {0}:{1}")).isFalse();
        assertThat(gluedFrenchLabel("Voir https://e-keneya.com à 10:30")).isFalse();
        assertThat(APPEND_COLON.matcher("summary.append(\": \").append(v)").find()).isTrue();
        assertThat(APPEND_COLON.matcher("url.append(\":\").append(port)").find()).isFalse();
    }
}
