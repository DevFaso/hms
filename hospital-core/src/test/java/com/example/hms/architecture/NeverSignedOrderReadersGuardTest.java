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
        Map.entry("fhir/everything/PatientEverythingService.java", new Reader("findIssuedByPatientIdAndHospitalIdIn(",
            "$everything and the patient record export: issued orders only, chosen in the query")),
        Map.entry("fhir/bulk/FhirBulkExportRunner.java", new Reader("PrescriptionIssuance.wasIssued(",
            "bulk export: issued orders only")),
        Map.entry("fhir/provider/MedicationRequestFhirResourceProvider.java", new Reader("findIssuedByPatientId(",
            "FHIR MedicationRequest search: issued orders only, chosen in the query (the read applies wasIssued)")),
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
            "ends a stay's orders; presents nothing")),
        Map.entry("service/emar/MarWardReader.java", new Reader("ON_MAR_STATUSES",
            "the ward MAR: SIGNED and TRANSMITTED orders only")),
        Map.entry("service/impl/OrderReviewHoldServiceImpl.java", new Reader("LOCKED_FOR_REVIEW",
            "review holds over the open orders and their replacement candidates; presents no medicine as taken")));

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
            assertThat(SourceScan.methodBody(medications, method)).as(method).contains(ISSUED);
        }
        assertThat(SourceScan.methodBody(read("service/PrescriptionServiceImpl.java"), "getPrescriptionsForPortalPatient"))
            .contains("PrescriptionIssuance.issuedOnly(")
            .doesNotContain("PrescriptionStatus.DRAFT");
        assertThat(medications).as("the old status-only test is gone").doesNotContain("isIssued(");
    }

    /**
     * QA D1537 review: every method of {@code PrescriptionRepository} that reads a patient's or a visit's
     * rows, read off the repository itself: a parameter named for a patient or an encounter, a derived
     * name on the patient or the encounter, or the ward MAR's query. A new such method is scanned for
     * without editing this test.
     */
    static java.util.Set<String> patientOrVisitReads() throws IOException {
        String repository = SourceScan.stripComments(Files.readString(
            ROOT.resolve("repository/PrescriptionRepository.java"), StandardCharsets.UTF_8));
        java.util.Set<String> methods = new TreeSet<>();
        // A declaration: a return type, the name, its balanced parameter list, then ';' (an annotation
        // is preceded by '@', a call has no type before it).
        Matcher declaration = Pattern.compile("[\\w>\\]]\\s++([A-Za-z_]\\w*+)\\s*+\\(").matcher(repository);
        while (declaration.find()) {
            String name = declaration.group(1);
            int open = repository.indexOf('(', declaration.end() - 1);
            int close = SourceScan.closingParen(repository, open);
            int after = close + 1;
            while (after < repository.length() && Character.isWhitespace(repository.charAt(after))) {
                after++;
            }
            if (after >= repository.length() || repository.charAt(after) != ';' || "new".equals(name)) {
                continue;
            }
            String params = repository.substring(open, close);
            if (params.contains("patientId") || params.contains("encounterId") || params.contains("UUID patient")
                || name.matches("(?:find|lock|count|exists)\\w*(?:Patient|Encounter|WardMar)\\w*")) {
                methods.add(name);
            }
        }
        return methods;
    }

    /**
     * QA D1537 review: the shared rule reads {@code withdrawnBeforeSigning}, recorded at withdrawal from the
     * status the order held before. One door writes it, the entity's status setter, which every withdrawal
     * path (the author's, the signed withdrawal, the stay's end, a review hold's stop) goes through; the
     * rule reads it, not the signature columns, which an order signed before V118 lacks.
     */
    @Test
    @DisplayName("the withdrawal fact is written only by the status setter, and the rule reads it")
    void theWithdrawalFactHasOneWriter() throws IOException {
        String entity = read("model/Prescription.java");
        assertThat(SourceScan.methodBody(entity, "setStatus"))
            .contains("before.isProposal()")
            .contains("this.withdrawnBeforeSigning =");
        assertThat(entity).containsPattern("@Setter\\(lombok\\.AccessLevel\\.NONE\\)\\s++private boolean withdrawnBeforeSigning");
        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = ROOT.relativize(file).toString().replace('\\', '/');
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                // The repository's queries read the column; they write nothing.
                if (!rel.equals("model/Prescription.java") && !rel.startsWith("repository/")
                    && Pattern.compile("withdrawnBeforeSigning\\s*+=[^=]|setWithdrawnBeforeSigning\\(").matcher(code).find()) {
                    writers.add(rel);
                }
            }
        }
        assertThat(writers).as("only Prescription.setStatus writes the withdrawal fact").isEmpty();
        String rule = read("service/support/PrescriptionIssuance.java");
        assertThat(rule).contains("isWithdrawnBeforeSigning()").doesNotContain("getSignedAt()");
    }

    @Test
    @DisplayName("the repository scan finds every patient and visit read, the named queries among them")
    void theRepositoryScanFindsTheReads() throws IOException {
        assertThat(patientOrVisitReads()).contains("findByPatient_Id", "findByPatient_IdAndHospital_IdIn",
            "findByEncounter_Id", "findSentAcrossHospitals", "findSentAcrossHospitalsAndStatusIn", "findWrittenSince",
            "findWardMarOrders", "lockUncompletedForReview", "findIssuedByPatientId", "findIssuedByPatientIdAndHospitalIdIn",
            "findIdsByEncounterIdAndStatusNotIn", "findByPatient_IdAndStatusIn");
    }

    private static boolean readsPrescriptionRows(String code) throws IOException {
        String alternatives = String.join("|", patientOrVisitReads());
        Matcher field = FIELD.matcher(code);
        while (field.find()) {
            if (Pattern.compile("\\b" + field.group(1) + "\\s*+\\.\\s*+(?:" + alternatives + ")\\s*+\\(")
                .matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private static String read(String rel) throws IOException {
        return SourceScan.stripComments(Files.readString(ROOT.resolve(rel), StandardCharsets.UTF_8));
    }
}
