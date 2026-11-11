package com.example.hms.architecture;

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
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1423: the clinician's snapshot listed 61 deactivated allergy rows as
 * allergies, because the shared read returns every row (the history needs them)
 * and that one reader did not filter. Every class that reads allergy rows is
 * listed with what it does with a deactivated one, and the source must still say
 * so, so a new reader is looked at instead of presenting history as current.
 */
@DisplayName("D1423: every reader of the allergy rows says what a deactivated row does there")
class AllergyActiveReadersGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    private static final Pattern READS_ROWS = Pattern.compile(
        "[aA]llergyRepository\\s*+\\.\\s*+(?:findByPatient_Id|findByPatient_IdIn|findByPatient_IdAndHospital_Id|findVisibleAt)\\("
            + "|rowsVisibleAt\\(");

    /** The token the source must hold, and why that handles a deactivated row. */
    private record Reader(String token, String reason) {
    }

    private static final Map<String, Reader> KNOWN = Map.ofEntries(
        Map.entry("service/PatientSnapshotServiceImpl.java",
            new Reader("filter(PatientAllergy::isActive)", "the drawer's list reads as current: active rows only")),
        Map.entry("service/impl/PatientStoryboardServiceImpl.java",
            new Reader("filter(PatientAllergy::isActive)", "the banner reads as current: active rows only")),
        Map.entry("cdshooks/service/PatientViewCdsService.java",
            new Reader("filter(PatientAllergy::isActive)", "the chart-open card: active rows only")),
        Map.entry("cdshooks/service/MedicationAllergyCdsService.java",
            new Reader("identity.matchingAllergies", "AllergyDrugIdentity and the class matchers skip inactive rows")),
        Map.entry("service/PrescriptionServiceImpl.java",
            new Reader("identity.matchingAllergies", "AllergyDrugIdentity and the class matchers skip inactive rows")),
        Map.entry("service/allergy/CurrentAllergyRecheck.java",
            new Reader("identity.matchingAllergies", "QA D1563/D1555: AllergyDrugIdentity, the class matchers and BetaLactamCrossReactivity skip inactive rows")),
        Map.entry("service/emar/MarWardReader.java",
            new Reader("findByPatient_IdIn", "hands every row to CurrentAllergyRecheck.cards, listed above, which skips inactive rows")),
        Map.entry("service/impl/NurseDashboardServiceImpl.java",
            new Reader("PatientAllergySummarySync.summarize", "the summary is built from active rows only")),
        Map.entry("service/allergy/PatientAllergySummarySync.java",
            new Reader("filter(PatientAllergy::isActive)", "the summary is built from active rows only")),
        Map.entry("service/PatientServiceImpl.java",
            new Reader("metadata.put(\"active\", allergy.isActive())",
                "the chart table and the timeline are the history: every row, each carrying its active flag")),
        Map.entry("service/EncounterServiceImpl.java",
            new Reader("ActiveAllergyDuplicates.find", "a duplicate check, which counts active rows only")),
        Map.entry("service/allergy/LegacyAllergyTextImporter.java",
            new Reader("allergyRepository.save", "an import's dedupe, shows nothing to a reader")),
        Map.entry("service/allergy/AllergySharingVisibility.java",
            new Reader("repository.findVisibleAt", "the shared read itself: every row, for the callers above")));

    @Test
    @DisplayName("a reader of the allergy rows is listed, and still handles a deactivated row as listed")
    void everyReaderIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = ROOT.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("repository/")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!READS_ROWS.matcher(code).find()) {
                    continue;
                }
                found.add(rel);
                Reader known = KNOWN.get(rel);
                if (known == null) {
                    problems.add(rel + " reads allergy rows and is not listed: active only, or history with a mark?");
                } else if (!code.contains(known.token())) {
                    problems.add(rel + " no longer holds `" + known.token() + "`: " + known.reason());
                }
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(found).as("a listed reader that no longer reads the rows comes off the list")
            .containsExactlyInAnyOrderElementsOf(KNOWN.keySet());
    }

    @Test
    @DisplayName("the snapshot's other deactivatable lists keep their active filter")
    void theSnapshotKeepsItsOtherActiveFilters() throws IOException {
        String snapshot = SourceScan.stripComments(Files.readString(ROOT.resolve("service/PatientSnapshotServiceImpl.java"),
            StandardCharsets.UTF_8));
        assertThat(snapshot)
            .as("problems").contains("p.getStatus() == ProblemStatus.ACTIVE")
            .as("legacy diagnoses").contains("DIAGNOSIS_STATUS_ACTIVE")
            .as("medications").contains("MedicationCourseStatus.isActive");
        String storyboard = SourceScan.stripComments(Files.readString(ROOT.resolve("service/impl/PatientStoryboardServiceImpl.java"),
            StandardCharsets.UTF_8));
        assertThat(storyboard).as("directives").contains("AdvanceDirectiveStatus.ACTIVE");
    }
}
