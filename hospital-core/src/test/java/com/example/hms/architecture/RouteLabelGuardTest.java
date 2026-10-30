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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1025 / D950 guard (backend half): a medication route composed into
 * text as its raw code. The chart review read "Morphine — 2 mg Toutes les 4
 * heures, INTRAVENOUS, 2 jours" to a French nurse. Every {@code getRoute()}
 * handed to a text builder ({@code join…(}, {@code Stream.of(},
 * {@code append…(}, a PDF {@code field(}) must go through
 * {@code MedicationRoutes.label(…)}. The portal half is
 * {@code npm run i18n:raw-enums}.
 */
@DisplayName("A medication route is never composed into text as its raw code")
class RouteLabelGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern TEXT_BUILDER = Pattern.compile(
        "\\b(?:joinNonBlank|join|Stream\\.of|append|appendIfPresent|field)\\(");

    /** Known raw routes, per {@code "file#line-free site"}. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "PrescriptionSmsDispatchServiceImpl rx.getRoute()", 1,
        "SMS offer to a partner pharmacy, in the partner channel's own format (no reader locale); "
            + "a pharmacist reads the prescribed code, not a translated label",
        "MedicationRequestFhirMapper src.getRoute()", 1,
        "FHIR dosage text, English by convention like every FHIR display (regression-guards.md)");

    @Test
    @DisplayName("getRoute() in a text builder is wrapped in MedicationRoutes.label")
    void routesAreLabelled() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String site : offenders(file.getFileName().toString().replace(".java", ""), source)) {
                    actual.merge(site, 1, Integer::sum);
                }
            }
        }
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a medication route composed into text as its raw code (QA D1025): use MedicationRoutes.label"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1025 shape")
    void catchesTheOldShape() {
        String before = "class C { void a() { x.summary(joinNonBlank(\", \", m.getFrequency(), m.getRoute())); } }";
        assertThat(offenders("C", before)).containsExactly("C m.getRoute()");
        String after = "class C { void a() { x.summary(joinNonBlank(\", \", m.getFrequency(), "
            + "MedicationRoutes.label(m.getRoute()))); } }";
        assertThat(offenders("C", after)).isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = TEXT_BUILDER.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            String args = source.substring(open + 1, SourceScan.closingParen(source, open));
            Matcher route = Pattern.compile("((?:\\w*+\\.)*+)\\bgetRoute\\(\\)").matcher(args);
            while (route.find()) {
                String before = args.substring(0, route.start());
                if (before.matches("(?s).*MedicationRoutes\\.label\\(\\s*$")) {
                    continue;
                }
                out.add(file + " " + route.group(1) + "getRoute()");
            }
        }
        return out;
    }
}
