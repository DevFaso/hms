package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D922 (user decision 2026-10-28, supersedes D654): a drug the catalogue
 * marks controlled is controlled whatever the prescriber ticked. Round 16
 * routed most decisions through {@code PrescriptionControlledResolver}, and
 * round 17 still found morphine signed with {@code controlled_substance = f}
 * and decisions keyed on the tick alone.
 *
 * <p>The class: a controlled-substance decision that reads the prescriber's
 * tick ({@code .isControlledSubstance()}) without the catalogue. Every read of
 * the tick in production code either sits in a statement that also asks the
 * catalogue ({@code controlledResolver.isControlled(}, a catalogue item's
 * {@code isControlled()}), or is listed below with why the tick itself is
 * meant there. A new read keyed on the tick alone fails.
 */
class ControlledFlagGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String TICK = ".isControlledSubstance()";

    private static final Map<String, GuardAllowlist.Allowance> ALLOWED = GuardAllowlist.allow(
        "service/pharmacy/PrescriptionControlledResolver.java", 1,
        "the resolver itself: the tick, then the catalogue",
        "service/pharmacy/ControlledSubstanceGuard.java", 2,
        "the prescriber two-factor clause: no two-factor step exists, so keying it on the catalogue would make "
            + "every morphine order unsignable (D922 note in the class; standing debt 'Prescriber two-factor step')",
        "service/PrescriptionServiceImpl.java", 2,
        "the tick's own edit rules: a prescriber cannot withdraw a tick (#463) and an edit compares the flags",
        "mapper/PrescriptionMapper.java", 1,
        "the response flag: the tick OR the resolved catalogue answer in the same expression",
        "mapper/PharmacyFillMapper.java", 1,
        "a pharmacy fill's own recorded flag (an outside fill), not a prescription",
        "service/impl/MedicationHistoryServiceImpl.java", 1,
        "a pharmacy fill's own recorded flag (an outside fill), not a prescription",
        "service/PatientRecordPdfService.java", 1,
        "a chart-review DTO whose flag ChartReviewServiceImpl already resolved through the catalogue");

    @Test
    void everyControlledDecisionAlsoAsksTheCatalogue() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                int[] starts = SourceScan.statementStarts(source);
                for (int at = source.indexOf(TICK); at >= 0; at = source.indexOf(TICK, at + 1)) {
                    int start = SourceScan.statementStartBefore(starts, at);
                    int end = source.indexOf(';', at);
                    String statement = source.substring(start, end < 0 ? source.length() : end);
                    boolean asksCatalogue = statement.contains("controlledResolver.isControlled(")
                        || statement.contains(".isControlled()");
                    if (!asksCatalogue) {
                        sites.add(rel);
                    }
                }
            }
        }
        Map<String, Integer> counts = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(counts, ALLOWED))
            .as("a controlled decision keyed on the prescriber's tick alone (QA D922: the catalogue decides too): "
                + "ask PrescriptionControlledResolver.isControlled")
            .isEmpty();
        assertThat(GuardAllowlist.stale(counts, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }
}
