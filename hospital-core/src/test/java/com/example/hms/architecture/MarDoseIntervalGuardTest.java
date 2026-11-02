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

    private static int count(Pattern pattern, String src) {
        Matcher m = pattern.matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
