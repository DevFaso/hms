package com.example.hms.service;

import com.example.hms.model.PatientAllergy;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.allergy.BetaLactamCrossReactivity;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1183 / D1149 (rounds 21 and 22): the drug-class allergy check knew the
 * generic names and missed the brands clinicians type: Bactrim passed a
 * "Sulfamides" allergy, Augmentin was signed for a penicillin-allergic patient.
 *
 * <p>Every allergy entry point is exercised with the brand typed free-text
 * and picked from the catalogue (whose item records the generic), and the
 * source of the code base is read so that a new place comparing an allergy
 * with a medication must go through the shared rule
 * ({@code DrugClassAllergy} / {@code BetaLactamCrossReactivity}) or say why
 * it is not a check.
 */
class DrugAllergyBrandEntryPointsTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Files that compare an allergy with a medication: each goes through the shared rule. */
    private static final Set<String> CHECKS = Set.of(
        "service/PrescriptionServiceImpl.java",
        "cdshooks/service/MedicationAllergyCdsService.java",
        "service/allergy/BetaLactamCrossReactivity.java");

    /** Files that read an allergy and a medication without comparing them, and why. */
    private static final Map<String, String> NOT_CHECKS = Map.of(
        "service/EncounterServiceImpl.java", "the nursing intake records allergies and medications side by side",
        "service/PatientRecordPdfService.java", "the record PDF prints both lists",
        "service/PatientServiceImpl.java", "the chart summary lists both",
        "service/PatientSnapshotServiceImpl.java", "the snapshot lists both");

    @ParameterizedTest(name = "{1} typed, allergy {0}: the prescription check blocks")
    @CsvSource({
        "Sulfamides, Bactrim 960 mg",
        "Sulfamides, Bactrim forte",
        "Sulfamides, Septra",
        "Pénicilline, Augmentin 1 g",
        "Amoxicilline, Augmentin 1 g",
        "penicillin, AUGMENTIN",
    })
    void thePrescriptionCheckBlocksABrandTypedFreeText(String allergen, String medication) {
        String med = PrescriptionServiceImpl.normalizeDrugText(medication);
        String enriched = PrescriptionServiceImpl.drugTextForAllergyCheck(medication, null);
        assertThat(PrescriptionServiceImpl.matchesAllergen(med, allergen, null)
            || PrescriptionServiceImpl.matchesAllergen(enriched, allergen, null)).isTrue();
    }

    @Test
    void thePrescriptionCheckReadsTheCatalogueItemsGenericName() {
        MedicationCatalogItem bactrim = new MedicationCatalogItem();
        bactrim.setNameFr("Bactrim Forte 800/160 mg");
        bactrim.setGenericName("Sulfaméthoxazole + Triméthoprime");
        String enriched = PrescriptionServiceImpl.drugTextForAllergyCheck("Bactrim Forte 800/160 mg", bactrim);
        assertThat(PrescriptionServiceImpl.matchesAllergen(enriched, "Sulfamides", null)).isTrue();

        // A catalogue brand the map does not know is still placed by its recorded generic.
        MedicationCatalogItem other = new MedicationCatalogItem();
        other.setNameFr("Marque locale 1 g");
        other.setGenericName("Amoxicilline + acide clavulanique");
        String typed = "Marque locale 1 g";
        assertThat(PrescriptionServiceImpl.matchesAllergen(PrescriptionServiceImpl.normalizeDrugText(typed),
            "Pénicilline", null)).as("the typed name alone").isFalse();
        assertThat(PrescriptionServiceImpl.matchesAllergen(
            PrescriptionServiceImpl.drugTextForAllergyCheck(typed, other), "Pénicilline", null)).isTrue();
    }

    @Test
    void theCdsClassRuleAndTheCrossReactivityAdvisoryReadTheBrand() {
        PatientAllergy penicillin = allergy("Pénicilline");
        assertThat(BetaLactamCrossReactivity.classMatchedAllergen("Augmentin 1 g", List.of(penicillin)))
            .contains("Pénicilline");
        assertThat(BetaLactamCrossReactivity.classMatchedAllergen("Bactrim forte", List.of(allergy("Sulfamides"))))
            .contains("Sulfamides");
        // A cephalosporin allergy and Augmentin ordered: the advisory sees a penicillin.
        assertThat(BetaLactamCrossReactivity.crossReactiveAllergen("Augmentin", List.of(allergy("Céphalosporines"))))
            .contains("Céphalosporines");
    }

    @Test
    void everyPlaceThatComparesAnAllergyWithAMedicationGoesThroughTheSharedRule() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        Set<String> bypassing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                boolean readsAllergy = src.contains("getAllergenDisplay()") || src.contains("getAllergenCode()");
                boolean readsMedication = src.toLowerCase(java.util.Locale.ROOT).contains("medication");
                if (!readsAllergy || !readsMedication || rel.equals("service/allergy/DrugClassAllergy.java")) {
                    continue;
                }
                if (CHECKS.contains(rel)) {
                    if (!src.contains("DrugClassAllergy.") && !rel.startsWith("service/allergy/")) {
                        bypassing.add(rel);
                    }
                } else if (!NOT_CHECKS.containsKey(rel)) {
                    unclassified.add(rel);
                }
            }
        }
        assertThat(unclassified)
            .as("a file reads an allergy and a medication (QA D1183): if it compares them, route it through"
                + " DrugClassAllergy (brands included) and list it in CHECKS; otherwise say why in NOT_CHECKS%s",
                SourceScan.MANDATE)
            .isEmpty();
        assertThat(bypassing).as("an allergy check that does not call DrugClassAllergy").isEmpty();
    }

    private static PatientAllergy allergy(String display) {
        PatientAllergy a = new PatientAllergy();
        a.setAllergenDisplay(display);
        a.setActive(true);
        return a;
    }
}
