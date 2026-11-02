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
        "service/allergy/BetaLactamCrossReactivity.java",
        "service/allergy/AllergyDrugIdentity.java");

    /**
     * QA D1183 (round 23): the stops (a block, not only an advisory) must go
     * through the INN identity and its safety net, so a name the rule cannot
     * resolve is never passed in silence.
     */
    private static final Set<String> STOPS = Set.of(
        "service/PrescriptionServiceImpl.java",
        "cdshooks/service/MedicationAllergyCdsService.java");

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
        // QA D1183 round 23: every brand the three areas typed.
        "Sulfamides, Septrin",
        "Sulfamides, Septrin forte 960 mg",
        "Sulfamides, Fansidar",
        "Sulfamides, Eusaprim",
        "Sulfamides, Bactrim",
        "Pénicilline, Totapen 500 mg",
        "Pénicilline, Orbénine 500 mg",
        "Pénicilline, Ospen 1 MUI",
        "Pénicilline, Augmentin",
        "Pénicilline, Clamoxyl 1 g",
        "Pénicilline, Extencilline 2,4 MUI",
        "Pénicilline, Oracilline",
        "Ampicilline, Totapen",
        "Cloxacilline, Orbénine",
        "penicilina, ospen",
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
        for (String stop : STOPS) {
            String src = SourceScan.stripComments(Files.readString(MAIN.resolve(stop), StandardCharsets.UTF_8));
            assertThat(src)
                .as("%s is an allergy STOP: it must read the drug through AllergyDrugIdentity and apply its"
                    + " safety net (QA D1183)", stop)
                .contains("AllergyDrugIdentity.of(")
                .contains("unverifiableClassAllergy(");
        }
    }

    /**
     * QA D1223 (round 24, D1149/D1183 NOT_FIXED): the tokens by which code reads
     * the catalogue item behind an order - its link, the item its code names,
     * the item the CDS engine resolved. An order also carries a typed name;
     * a check reading only the item judged "Bristopen" sent with paracetamol's
     * id as paracetamol.
     */
    private static final java.util.regex.Pattern CATALOGUE_IDENTITY_READ = java.util.regex.Pattern.compile(
        "getMedicationCatalogItemId\\(\\)|findByHospitalIdAndCode\\(|resolveCatalogItem\\("
            + "|findActiveByHospitalIdAndRxnormCode\\(|proposedCatalogItem\\(\\)|\\.catalogItem\\(\\)");

    /** Readers of an order's catalogue item that decide safety: each goes through the one agreement rule. */
    private static final Set<String> IDENTITY_CHECKS = Set.of(
        "service/PrescriptionServiceImpl.java",
        "service/pharmacy/DispenseVerificationService.java",
        "cdshooks/service/MedicationAllergyCdsService.java",
        "cdshooks/rules/OrderedDrugIdentityRule.java");

    /** Readers of an order's catalogue item that are not an identity decision, and why. */
    private static final Map<String, String> NOT_IDENTITY_CHECKS = Map.ofEntries(
        Map.entry("cdshooks/rules/CdsRuleEngine.java", "builds the rule context; OrderedDrugIdentityRule, a rule of"
            + " every engine, flags a name that contradicts the item it resolved"),
        Map.entry("cdshooks/rules/PediatricDoseRule.java", "reads the ceiling of the context's item; the engine's"
            + " OrderedDrugIdentityRule flags a contradiction, and the prescription service refuses one first"),
        Map.entry("cdshooks/service/MedicationPrescribeRulesCdsService.java", "an INFO card with the item's tall-man"
            + " lettering: display, no safety decision"),
        Map.entry("cdshooks/service/OrderSelectRulesCdsService.java", "names the engine's resolver in a comment"),
        Map.entry("mapper/PrescriptionMapper.java", "copies the link and reads the controlled flag of a SAVED order,"
            + " whose name and item agree since the prescription service refuses a contradiction (an older row"
            + " is refused at signature and fails the dispense DRUG check)"),
        Map.entry("service/pharmacy/PrescriptionControlledResolver.java", "the controlled flag of a SAVED order, as"
            + " for the mapper"),
        Map.entry("service/pharmacy/DispenseServiceImpl.java", "the product the pharmacist dispenses, not the order;"
            + " the lot is checked against the order by DispenseVerificationService"),
        Map.entry("service/pharmacy/InventoryServiceImpl.java", "a stock item, no order"),
        Map.entry("service/pharmacy/PharmacySaleServiceImpl.java", "an over-the-counter sale line, no order"),
        Map.entry("service/pharmacy/StockOutRoutingServiceImpl.java", "stock availability of a SAVED order; the"
            + " DRUG check still runs on the fill"));

    @Test
    void everyReaderOfAnOrdersCatalogueItemIsClassifiedAndTheChecksUseTheAgreementRule() throws IOException {
        Set<String> readers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("repository/")) {
                    continue;
                }
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (CATALOGUE_IDENTITY_READ.matcher(src).find()) {
                    readers.add(rel);
                }
            }
        }
        assertThat(readers).as("the scan reaches the readers").contains("service/PrescriptionServiceImpl.java");
        Set<String> unclassified = new TreeSet<>(readers);
        unclassified.removeAll(IDENTITY_CHECKS);
        unclassified.removeAll(NOT_IDENTITY_CHECKS.keySet());
        assertThat(unclassified)
            .as("a file reads the catalogue item behind an order (QA D1223): if it decides safety from it, route it"
                + " through OrderedDrugIdentity (the typed name must agree with the item) and list it in"
                + " IDENTITY_CHECKS; otherwise say why in NOT_IDENTITY_CHECKS%s", SourceScan.MANDATE)
            .isEmpty();
        Set<String> stale = new TreeSet<>(NOT_IDENTITY_CHECKS.keySet());
        stale.removeAll(readers);
        assertThat(stale).as("NOT_IDENTITY_CHECKS entries that no longer read the item: delete them").isEmpty();
        for (String check : IDENTITY_CHECKS) {
            String src = SourceScan.stripComments(Files.readString(MAIN.resolve(check), StandardCharsets.UTF_8));
            assertThat(src.contains("OrderedDrugIdentity.") || src.contains("AllergyDrugIdentity.of("))
                .as("%s decides from the catalogue item behind an order: it must ask OrderedDrugIdentity (or the"
                    + " allergy identity built on it) whether the typed name names that item (QA D1223)", check)
                .isTrue();
        }
        // The allergy identity itself never lets an item resolve a name it does not name.
        assertThat(SourceScan.stripComments(Files.readString(MAIN.resolve("service/allergy/AllergyDrugIdentity.java"),
            StandardCharsets.UTF_8))).contains("OrderedDrugIdentity.agrees(");
        // The engine-wide card is a Spring rule, so every engine (the three CDS hooks, the prescription) runs it.
        assertThat(Files.readString(MAIN.resolve("cdshooks/rules/OrderedDrugIdentityRule.java"), StandardCharsets.UTF_8))
            .contains("@Component").contains("implements CdsRule");
    }

    private static PatientAllergy allergy(String display) {
        PatientAllergy a = new PatientAllergy();
        a.setAllergenDisplay(display);
        a.setActive(true);
        return a;
    }
}
