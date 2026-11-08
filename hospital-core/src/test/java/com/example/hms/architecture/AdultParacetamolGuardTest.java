package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 34 (adult paracetamol, class: a dose limit one path does not reach). Every prescribing
 * path, the CDS hooks and the signature run the one engine, and the engine is the one place a rule
 * context is built, so no path can reach the rules without the paracetamol facts. (A CDS hook sends
 * no frequency, so there the daily total counts one dose of its draft: tasklist.md.) The rule stays
 * what the guidance calls it, an alert, with one card above that: the contraindication, which saves
 * only with a reason. CR-02 (provisional, awaiting the named approver): a finding outside the limits
 * stays a warning on the draft and holds the signature for the prescriber's exception. No hard stop
 * or review was asked, and raising another card is a clinician's decision, not a side effect.
 */
@DisplayName("Adult paracetamol: one engine reaches every path, and its cards stay alerts")
class AdultParacetamolGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path RULE = MAIN.resolve("cdshooks/rules/AdultParacetamolRule.java");
    private static final Path ENGINE = MAIN.resolve("cdshooks/rules/CdsRuleEngine.java");
    /** QA D1515: a read of a patient's prescriptions, the repository's patient finders, whatever the field is called. */
    private static final java.util.regex.Pattern PRESCRIPTION_READ =
        java.util.regex.Pattern.compile("(?i)prescriptions?+(?:Repository)?+\\.findByPatient_Id");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void onlyTheEngineBuildsARuleContext() throws IOException {
        Set<String> builders = new TreeSet<>();
        for (Path source : javaSources()) {
            if (code(source).contains("new CdsRuleContext(")) {
                builders.add(MAIN.relativize(source).toString().replace('\\', '/'));
            }
        }
        // CR-01: the record's own copy with a route is the only other: it keeps every field it was built with.
        assertThat(builders).containsExactlyInAnyOrder("cdshooks/rules/CdsRuleEngine.java",
            "cdshooks/rules/CdsRuleContext.java");
    }

    @Test
    void theEngineReadsTheFactsForEveryContext() throws IOException {
        assertThat(code(ENGINE)).contains("readParacetamolFacts(patient, hospitalId, proposedMedicationName, catalogItem, excludedPrescriptionId)")
            .contains("return paracetamolFacts.read(patient, hospitalId, name, item, editedOrderId);");
    }

    @Test
    void theRuleOnlyEverAlerts() throws IOException {
        assertThat(code(RULE))
            .contains("ALERT = CdsCard.Indicator.WARNING")
            .containsOnlyOnce("Indicator.CRITICAL")
            .contains("contraindication.detail")
            // The contraindication, and the held finding: never a hardStop, never a reviewRequired.
            .contains("null, null, null, UUID.randomUUID().toString(), null, null, Boolean.TRUE);")
            .contains("null, null, null, UUID.randomUUID().toString(), null, null, Boolean.TRUE, key);")
            .contains("null, null, null, UUID.randomUUID().toString());");
    }

    /**
     * CR-03 and the review of this wave: the exposure is read after the dose commits and in a transaction
     * of its own, so a failed read cannot undo a dose, and the MAR takes no patient lock: death recording
     * writes the patient and then its orders, the opposite order of a MAR write.
     */
    @Test
    void theExposureIsReadOutsideTheDoseAndTheMarTakesNoPatientLock() throws IOException {
        assertThat(code(MAIN.resolve("service/emar/ParacetamolExposureCheck.java")))
            .contains("propagation = Propagation.REQUIRES_NEW")
            .doesNotContain("readOnly");
        assertThat(code(MAIN.resolve("service/impl/NurseTaskServiceImpl.java")))
            .contains("TransactionCallbacks.afterCommit(")
            .doesNotContain("lockByIdUnscoped");
    }

    /**
     * QA D1515 (class: an "active" list read with a status set of its own, which counted a draft as an
     * order the patient takes). Every CDS file that reads a patient's prescriptions keeps only the ones
     * {@code CdsRuleEngine.inForce} keeps, the one reading of {@code PrescriptionStatus.isInForce()}, and
     * none holds a status set of its own.
     */
    @Test
    void everyCdsReaderOfOrdersCountsOnlyOrdersInForce() throws IOException {
        Path cds = MAIN.resolve("cdshooks");
        Set<String> readers = new TreeSet<>();
        Set<String> ownSets = new TreeSet<>();
        for (Path source : javaSources()) {
            if (!source.startsWith(cds)) {
                continue;
            }
            String code = code(source);
            String name = MAIN.relativize(source).toString().replace('\\', '/');
            if (code.contains("Set<PrescriptionStatus>") || code.contains("PrescriptionStatus.DRAFT")
                || code.contains("PrescriptionStatus.CANCELLED")) {
                ownSets.add(name);
            }
            if (PRESCRIPTION_READ.matcher(code).find()) {
                readers.add(name);
                assertThat(code).as(name + ": its read of the orders keeps only orders in force")
                    .contains("CdsRuleEngine::inForce");
            }
        }
        assertThat(ownSets).as("a status set of its own beside PrescriptionStatus.isInForce()").isEmpty();
        // The three readers: the engine's context, the paracetamol facts and the BPA engine. A new one is checked above.
        assertThat(readers).containsExactlyInAnyOrder("cdshooks/rules/CdsRuleEngine.java",
            "cdshooks/rules/ParacetamolFactsReader.java", "cdshooks/bpa/BpaRuleEngine.java");
        assertThat(code(MAIN.resolve("cdshooks/rules/CdsRuleEngine.java")))
            .contains("return order != null && order.getStatus() != null && order.getStatus().isInForce();");
    }

    @Test
    void childrenStayWithThePaediatricRule() throws IOException {
        assertThat(code(RULE)).contains("PediatricDoseRule.isAdult(context.patient())")
            .contains("PediatricDoseRule.dosesOnADosingDay(")
            .doesNotContain("Period.between");
    }
}
