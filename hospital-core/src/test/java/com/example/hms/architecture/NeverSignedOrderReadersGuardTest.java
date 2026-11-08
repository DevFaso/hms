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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 (round 40): drafts cancelled before anyone signed them showed to the patient as past
 * medicines ("Prescrit par ... Interrompu") and counted in the health summary and the prescriptions
 * list. The readers excluded DRAFT and PENDING_SIGNATURE by status, and a cancelled draft's status is
 * CANCELLED, the word a signed order takes when it is stopped. {@code PrescriptionIssuance} is the one
 * rule that tells them apart, by the signature.
 *
 * <p>Every class that reads a patient's (or a visit's) prescription rows is listed with how it treats
 * a never-signed order, and the source must still say so: a new reader is looked at instead of
 * presenting a discarded draft as a medicine the patient took.
 */
@DisplayName("D1537: every reader of a patient's prescriptions says what a never-signed order does there")
class NeverSignedOrderReadersGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    private static final Pattern FIELD = Pattern.compile("\\bPrescriptionRepository\\s++(\\w++)\\s*+[;=,)]");

    /** The token the source must hold, and why that handles a never-signed order. */
    private record Reader(String token, String reason) {
    }

    private static final String ISSUED = "PrescriptionIssuance::wasIssued";

    private static final Map<String, Reader> KNOWN = Map.ofEntries(
        // The patient's own readers, the proxy's, the health summary, the patient apps (same API).
        Map.entry("service/impl/PatientMedicationServiceImpl.java", new Reader(ISSUED,
            "the patient's and the proxy's list, page and count: issued orders only")),
        Map.entry("service/PrescriptionServiceImpl.java", new Reader("PrescriptionIssuance.issuedOnly(",
            "/me/patient/prescriptions: issued orders only; the staff lists carry each row's status")),
        // Exports.
        Map.entry("fhir/everything/PatientEverythingService.java", new Reader("PrescriptionIssuance.issuedOnly(",
            "$everything and the patient record export: issued orders only")),
        Map.entry("fhir/bulk/FhirBulkExportRunner.java", new Reader("PrescriptionIssuance.wasIssued(",
            "bulk export: issued orders only")),
        Map.entry("fhir/provider/MedicationRequestFhirResourceProvider.java", new Reader(ISSUED,
            "FHIR MedicationRequest search and read: issued orders only")),
        // The clinician's medication history, presented as courses.
        Map.entry("service/impl/MedicationHistoryServiceImpl.java", new Reader("PrescriptionIssuance.withoutDiscardedProposals(",
            "the medication timeline: a live draft stays, a discarded one is no course")),
        Map.entry("service/PatientSnapshotServiceImpl.java", new Reader("MedicationCourseStatus.isActive",
            "the snapshot lists running courses only: no draft, no withdrawn order")),
        // Visit documents: continued at discharge.
        Map.entry("service/DischargeSummaryServiceImpl.java", new Reader("isWithdrawn()",
            "reconciliation lists continued orders: no draft, no withdrawn order")),
        Map.entry("service/EncounterServiceImpl.java", new Reader("isWithdrawn()",
            "the after-visit reconciliation lists continued orders: no draft, no withdrawn order")),
        Map.entry("service/AdmissionStayEncounterService.java", new Reader("isWithdrawn()",
            "take-home orders: no draft, no withdrawn order")),
        // Staff history that names each row's status.
        Map.entry("service/PatientServiceImpl.java", new Reader("META_STATUS",
            "the staff timeline and the doctor's record: history, each row carrying its status")),
        Map.entry("service/impl/ChartReviewServiceImpl.java", new Reader(".status(p.getStatus() != null ? p.getStatus().name() : null)",
            "chart review: history, each row carrying its status")),
        // Decision support and pharmacy: orders in force only.
        Map.entry("cdshooks/rules/CdsRuleEngine.java", new Reader("CdsRuleEngine::inForceOrProposed",
            "in force or a live proposal, never a withdrawn order")),
        Map.entry("cdshooks/bpa/BpaRuleEngine.java", new Reader("CdsRuleEngine::inForce",
            "orders in force only")),
        Map.entry("cdshooks/rules/ParacetamolFactsReader.java", new Reader("CdsRuleEngine::inForce",
            "orders in force only")),
        Map.entry("service/pharmacy/CdsCheckServiceImpl.java", new Reader("ACTIVE_STATUSES.contains",
            "active statuses only, none withdrawn or unsigned")),
        Map.entry("service/pharmacy/MtmReviewServiceImpl.java", new Reader("ACTIVE_STATUSES.contains",
            "active statuses only, none withdrawn or unsigned")),
        Map.entry("service/StayOrderDiscontinuation.java", new Reader("ENDABLE_STATUSES",
            "ends a stay's orders; presents nothing")));

    @Test
    @DisplayName("a reader of a patient's prescriptions is listed, and still handles a never-signed order as listed")
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
                if (!readsPrescriptionRows(code)) {
                    continue;
                }
                found.add(rel);
                Reader known = KNOWN.get(rel);
                if (known == null) {
                    problems.add(rel + " reads a patient's prescriptions and is not listed: does it present a"
                        + " never-signed order as taken? Use PrescriptionIssuance.");
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
    @DisplayName("each patient-facing read path applies the shared rule, not a status list of its own")
    void thePatientFacingPathsUseTheSharedRule() throws IOException {
        String medications = read("service/impl/PatientMedicationServiceImpl.java");
        for (String method : List.of("getMedicationsPageForPatientPortal", "countMedicationsForPatientPortal", "getMedications")) {
            assertThat(methodBody(medications, method)).as(method).contains(ISSUED);
        }
        assertThat(methodBody(read("service/PrescriptionServiceImpl.java"), "getPrescriptionsForPortalPatient"))
            .contains("PrescriptionIssuance.issuedOnly(")
            .doesNotContain("PrescriptionStatus.DRAFT");
        assertThat(medications).as("the old status-only test is gone").doesNotContain("isIssued(");
    }

    private static boolean readsPrescriptionRows(String code) {
        Matcher field = FIELD.matcher(code);
        while (field.find()) {
            if (Pattern.compile("\\b" + field.group(1) + "\\s*+\\.\\s*+(?:findByPatient_\\w*|findByEncounter_\\w*)\\(")
                .matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private static String read(String rel) throws IOException {
        return SourceScan.stripComments(Files.readString(ROOT.resolve(rel), StandardCharsets.UTF_8));
    }

    /** The body of the first method declared with this name, or null. */
    private static String methodBody(String code, String name) {
        Matcher m = Pattern.compile("[\\w>\\]]\\s++" + name + "\\s*+\\(").matcher(code);
        while (m.find()) {
            int open = code.indexOf('(', m.start());
            int close = SourceScan.closingParen(code, open);
            int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
            int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                continue;
            }
            return code.substring(brace, SourceScan.closingBrace(code, brace) + 1);
        }
        return null;
    }
}
