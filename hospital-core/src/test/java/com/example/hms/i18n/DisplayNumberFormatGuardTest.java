package com.example.hms.i18n;

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
 * QA D1106 guard (the D1002 locale-number class, round 19): a decimal written
 * into text a person reads — a chronology title, a record-PDF row, a summary
 * line, a refusal — with the stored decimal point, so a French reader saw
 * "Kaliémie 5.9 mmol/L" beside "≥ 38,5 °C". Round 18 guarded only the CDS
 * cards ({@code CdsCardLocaleFormatGuardTest}); the chronologies and the PDF
 * were siblings it could not see.
 *
 * <p>Every main class is scanned for the shapes that bypass
 * {@code utility/DisplayNumbers}:
 * <ul>
 *   <li>{@code toPlainString()} — the machine form of a BigDecimal;</li>
 *   <li>{@code String.format} with a floating-point conversion ({@code %.2f});</li>
 *   <li>a lab value ({@code getResultValue()}) appended, concatenated or joined
 *       into text as stored;</li>
 *   <li>a decimal measurement (temperature, weight, height, head circumference,
 *       BMI) turned into a string by hand ({@code String.valueOf}, {@code + ""},
 *       {@code .toString()}, {@code append}).</li>
 * </ul>
 * A Number handed to a MessageSource is not matched: MessageFormat formats it in
 * the message's locale. Wire formats (HL7, a JSON or CSV export, a comparison
 * key) are allowed below, each with its reason.
 */
@DisplayName("Server display text writes decimals through DisplayNumbers")
class DisplayNumberFormatGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final String DECIMAL_GETTER =
        "get(?:Temperature\\w*|WeightKg|HeightCm|HeadCircumferenceCm|Bmi|Muac\\w*)\\(\\)";

    private static final Pattern RAW_DECIMAL = Pattern.compile(
        "\\.toPlainString\\(\\)"
            + "|String\\.format\\((?:[^;%]|%(?![-+ 0#,]*+\\d*+(?:\\.\\d++)?+[fFeEgG]))*+%[-+ 0#,]*+\\d*+(?:\\.\\d++)?+[fFeEgG]"
            + "|\\.append\\(\\s*[\\w.]*getResultValue\\(\\)\\s*\\)"
            + "|getResultValue\\(\\)\\s*\\+"
            + "|\\+\\s*[\\w.()]*?\\bgetResultValue\\(\\)"
            + "|\\bjoin\\([^;]*?,\\s*[\\w.]*getResultValue\\(\\)"
            + "|String\\.valueOf\\(\\s*[\\w.]*" + DECIMAL_GETTER + "\\s*\\)"
            + "|" + DECIMAL_GETTER + "\\s*\\+|\\+\\s*[\\w.]*" + DECIMAL_GETTER
            + "|" + DECIMAL_GETTER + "\\.toString\\(\\)"
            + "|\\.append\\(\\s*[\\w.]*" + DECIMAL_GETTER + "\\s*\\)");

    /**
     * True false positives only: machine formats where the decimal point is the
     * format. Shrink only; a display site is never listed here.
     */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "utility/Hl7v2MessageBuilder.java", 2,
            "HL7 v2 OBX on the wire (value type + OBX-5): the value goes out as the laboratory entered it",
        "service/pharmacy/PharmacyClaimExportService.java", 1,
            "insurer claim export is JSON: a JSON number is written with a point",
        "service/pharmacy/ControlledDrugRegisterService.java", 1,
            "controlled-drug register CSV export: a machine-readable column, not display text",
        "service/PdfInvoiceService.java", 1,
            "money: the digits of an amount, grouped and suffixed F CFA by the invoice formatter itself (D621)",
        "service/CriticalValueNotificationService.java", 1,
            "normalizeForComparison: a comparison key for repeat-critical detection, never shown");

    @Test
    @DisplayName("no main class composes a decimal into display text by hand")
    void decimalsAreFormattedForTheReader() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            if (file.endsWith("DisplayNumbers.java")) {
                continue;
            }
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            int hits = offenders(source).size();
            if (hits > 0) {
                actual.put(MAIN.relativize(file).toString().replace('\\', '/'), hits);
            }
        }
        assertThat(files).as("main classes scanned").hasSizeGreaterThan(1000);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a decimal is written into display text with the stored point (QA D1106, D1002 class): use "
                + "DisplayNumbers.labValue / decimal / quantity / measure" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1106 shapes and leaves the formatted ones")
    void catchesTheOldShapes() {
        assertThat(offenders("""
            summary.append(": ").append(result.getResultValue());
            return r.getResultValue() + " " + r.getResultUnit();
            r.isReleased() ? join(" ", r.getResultValue(), r.getResultUnit()) : pending;
            String amount = remaining.stripTrailingZeros().toPlainString();
            .absoluteChange(String.format("%.2f %s", absoluteChange, unit));
            String t = String.valueOf(v.getTemperatureCelsius());
            String w = v.getWeightKg() + " kg";
            """)).hasSize(7);
        assertThat(offenders("""
            summary.append(": ").append(DisplayNumbers.labValue(result.getResultValue()));
            return DisplayNumbers.labValueWithUnit(r.getResultValue(), r.getResultUnit(), locale);
            r.isReleased() ? join(" ", DisplayNumbers.labValue(r.getResultValue(), t.locale()), r.getResultUnit()) : p;
            String amount = DisplayNumbers.quantity(remaining, locale);
            MessageUtil.resolve("vitals.summary.temperature", DisplayNumbers.measure(v.getTemperatureCelsius(), 1, l));
            alertText("labor-maternal-fever", entry.getTemperatureCelsius());
            String.format("[%s] %s", a, b);
            if (r.getResultValue() == null) return null;
            """)).isEmpty();
    }

    static List<String> offenders(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = RAW_DECIMAL.matcher(source);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
