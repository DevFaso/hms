package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1049 / D1078 guard (the D1002 locale-number class, round 18): a date or
 * a measured value written into a CDS or Best-Practice-Advisory card in a
 * fixed format, so a French clinician reads "prescrit le 2026-10-30" or
 * "Dernière température : 38.9 °C" next to "≥ 38,5 °C" in the same card.
 *
 * <p>Every class that writes card text (it uses {@code CdsCardText},
 * {@code BpaCardText} or a {@code "cds.*"} message key) must format dates
 * through {@code CdsCardText.date(…)} and decimals through
 * {@code CdsCardText.decimal(…)}. The scan refuses the shapes that bypass
 * them: a {@code String.format} with a floating-point conversion, a
 * {@code BigDecimal.toPlainString()}, a {@code toString()} of a date/time
 * ({@code toLocalDate().toString()}, {@code getCreatedAt().toString()}), an ISO
 * formatter. Integers (a NEWS2 score, a heart rate) are left alone: they carry
 * no separator.
 */
@DisplayName("CDS/BPA cards format dates and decimals for the reader")
class CdsCardLocaleFormatGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern WRITES_CARD_TEXT = Pattern.compile(
        "\\bCdsCardText\\b|\\bBpaCardText\\b|\"cds\\.[a-zA-Z]");
    private static final Pattern FIXED_FORMAT = Pattern.compile(
        "String\\.format\\([^;]*?%[-+ 0#,]*\\d*(?:\\.\\d+)?[fFeEgG]"
            + "|\\.toPlainString\\(\\)"
            + "|toLocal(?:Date|Time|DateTime)\\(\\)\\.toString\\(\\)"
            + "|(?:Date|At|Time|DateTime)\\(\\)\\.toString\\(\\)"
            + "|DateTimeFormatter\\.ISO_");

    /**
     * Sites that still format a card value by hand, each with why. Shrink only.
     * Empty since the round-18 merge: PediatricDoseRule (the paediatric mg/kg
     * card) now writes its values through CdsCardText.decimal(…).
     */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();

    @Test
    @DisplayName("no card text formats a date or a decimal by hand")
    void cardValuesAreFormattedForTheReader() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        int cardClasses = 0;
        for (Path file : files) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!WRITES_CARD_TEXT.matcher(source).find() || file.endsWith("CdsCardText.java")) {
                continue;
            }
            cardClasses++;
            int hits = offenders(source).size();
            if (hits > 0) {
                actual.put(MAIN.relativize(file).toString().replace('\\', '/'), hits);
            }
        }
        assertThat(cardClasses).as("classes that write CDS/BPA card text").isGreaterThanOrEqualTo(12);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a CDS/BPA card formats a date or a decimal itself, so the reader sees an ISO date or a decimal "
                + "point (QA D1049, D1078): use CdsCardText.date(…) / CdsCardText.decimal(…)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1049 and D1078 shapes")
    void catchesTheOldShapes() {
        assertThat(offenders("""
            text.get("cds.duplicate.summary", "…", existing.getMedicationName(),
                existing.getCreatedAt().toLocalDate().toString());
            text.get("cds.bpa.malaria.temperature", "…",
                String.format(Locale.ROOT, "%.1f", feverEvent.getTemperatureCelsius()), when);
            """)).hasSize(2);
        assertThat(offenders("""
            text.get("cds.duplicate.summary", "…", name, text.date(existing.getCreatedAt().toLocalDate()));
            text.get("cds.bpa.malaria.temperature", "…", text.decimal(t, 1), when);
            text.get("cds.bpa.qsofa.met", "…", String.valueOf(score.total()));
            String.format("[%s] %s", a, b);
            """)).isEmpty();
    }

    static List<String> offenders(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = FIXED_FORMAT.matcher(source);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
