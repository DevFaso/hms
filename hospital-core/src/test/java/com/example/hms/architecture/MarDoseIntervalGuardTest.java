package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1210 (round 23): morphine q4h was recorded GIVEN twice a minute apart
 * because the administration paths only checked the slot, never the time
 * since the last real dose. Every write that stamps an administration time
 * on a MAR row ({@code setAdministeredAt(} / {@code .administeredAt(}) in a
 * file that handles {@code MedicationAdministrationRecord} must run the
 * minimum-interval rule ({@code requireDoseInterval(}, which applies
 * {@code MarDoseInterval}) at least once per write. A new administration path
 * that forgets it fails here.
 */
class MarDoseIntervalGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern WRITE = Pattern.compile("\\.(?:setAdministeredAt|administeredAt)\\(");

    private static final Pattern RULE_CALL = Pattern.compile("\\brequireDoseInterval\\(");

    /** The rule's own definition in a file, which is not a call. */
    private static final Pattern RULE_DEFINITION = Pattern.compile("private String requireDoseInterval\\(");

    /** Files that write the field without recording an administration, and why. */
    private static final Map<String, String> NOT_ADMINISTRATIONS = Map.of(
        "model/MedicationAdministrationRecord.java", "the entity itself (its builder and setters)");

    @Test
    void everyAdministrationWriteRunsTheMinimumIntervalRule() throws IOException {
        Map<String, String> missing = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!src.contains("MedicationAdministrationRecord") || NOT_ADMINISTRATIONS.containsKey(rel)) {
                    continue;
                }
                int writes = count(WRITE, src);
                if (writes == 0) {
                    continue;
                }
                scanned++;
                int calls = count(RULE_CALL, src) - count(RULE_DEFINITION, src);
                if (calls < writes) {
                    missing.put(rel, writes + " administration write(s), " + calls + " interval check(s)");
                }
            }
        }
        assertThat(scanned).as("the scan found the eMAR administration paths").isPositive();
        assertThat(missing)
            .as("a MAR administration path that does not run the D1210 minimum-interval rule"
                + " (requireDoseInterval / MarDoseInterval)%s", SourceScan.MANDATE)
            .isEmpty();
    }

    /** What every write of a dose or a bedside check on a MAR row must ask first (QA D1392). */
    private static final Pattern MAR_WRITE = Pattern.compile("\\.(?:setAdministeredAt|administeredAt|setScanVerifiedAt)\\(");
    private static final String STANDING_RULE = "requireOrderStillStands(";

    /**
     * QA D1392: a cancelled order took a five-rights check and a GIVEN dose,
     * because the paths asked whether the stay had ended and never what the
     * order's own status was. A file that writes a MAR row runs the order's
     * standing rule, and the two doors into those writes do so before anything
     * else happens. A new administration path that forgets it fails here.
     */
    @Test
    void everyMarWriteAsksWhetherTheOrderStillStands() throws IOException {
        Map<String, String> missing = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (src.contains("MedicationAdministrationRecord") && !NOT_ADMINISTRATIONS.containsKey(rel)
                    && MAR_WRITE.matcher(src).find()) {
                    scanned++;
                    if (!src.contains(STANDING_RULE)) {
                        missing.put(rel, "writes a MAR row without " + STANDING_RULE);
                    }
                }
            }
        }
        assertThat(scanned).as("the scan found the eMAR write paths").isPositive();
        assertThat(missing).as("a MAR write that does not refuse a withdrawn order (QA D1392)%s", SourceScan.MANDATE).isEmpty();

        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/impl/NurseTaskServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(count(Pattern.compile("requireOrderStillStands\\(\\w+(?:\\.getPrescription\\(\\))?, hospitalId\\)"), service))
            .as("the prescription path and the MAR-row path of the administer call, both doors of the bedside check")
            .isEqualTo(4);
    }

    private static int count(Pattern pattern, String src) {
        Matcher m = pattern.matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
