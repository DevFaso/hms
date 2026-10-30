package com.example.hms.architecture;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D922 guard (decision 2026-10-28): a dose recorded without the witness rule.
 *
 * <p>A nurse gave morphine from the station MAR and the eMAR with nobody
 * asked to witness it. The rule now lives in
 * {@code ControlledAdministrationWitness}; this guard keeps every path that
 * records a dose's outcome going through it. Any production method that sets
 * a medication-administration record's status from a variable (the outcome
 * the nurse chose: {@code setStatus(status)}, {@code .status(marStatus)}) must
 * write the witness onto the record ({@code .witnessUser(…)},
 * {@code setWitnessUser(…)}) or call the rule in the same method. A sweep that writes
 * a constant ({@code MISSED}, {@code PENDING}) records no dose and is not
 * matched.
 */
@DisplayName("Every recorded dose outcome goes through the witness rule")
class MarWitnessGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s+)+[\\w<>,.? \\[\\]]+\\s+(\\w+)\\s*\\([^;{]*?\\)\\s*(?:throws [\\w., ]+)?\\{");
    /** The witness written onto the record, or the rule called, in the same method. */
    private static final Pattern WITNESS_STAMP = Pattern.compile(
        "\\.witnessUser\\(|\\bsetWitnessUser\\(|\\bcontrolledAdministrationWitness\\.resolve\\(");

    /** A MAR record's status set from a variable: on the record, or in its builder chain. */
    private static final Pattern OUTCOME = Pattern.compile(
        "\\bmar\\w*\\.setStatus\\(\\s*(?:marStatus|status|outcome|administrationStatus)\\s*\\)"
            + "|MedicationAdministrationRecord\\.builder\\(\\)[^;]*?\\.status\\(\\s*(?:marStatus|status|outcome|administrationStatus)\\s*\\)");

    @Test
    @DisplayName("a method that records a MAR outcome names the witness")
    void outcomesGoThroughTheWitness() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                offenders.addAll(offenders(file.getFileName().toString().replace(".java", ""), source));
            }
        }
        assertThat(offenders)
            .as("a MAR dose outcome is recorded without ControlledAdministrationWitness (QA D922)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D922 shape")
    void catchesTheOldShape() {
        String before = """
            class S {
                private Dto persistMarRecord(Prescription rx, MedicationAdministrationStatus status) {
                    MedicationAdministrationRecord marRecord = MedicationAdministrationRecord.builder()
                        .prescription(rx).status(status).build();
                    return save(marRecord);
                }
            }
            """;
        assertThat(offenders("S", before)).containsExactly("S#persistMarRecord");
        String after = before.replace(".status(status).build()", ".status(status).witnessUser(witness).build()");
        assertThat(offenders("S", after)).isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (OUTCOME.matcher(body).find() && !WITNESS_STAMP.matcher(body).find()) {
                out.add(file + "#" + m.group(1));
            }
        }
        return out;
    }
}
