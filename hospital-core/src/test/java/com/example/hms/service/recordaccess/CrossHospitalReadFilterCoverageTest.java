package com.example.hms.service.recordaccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8 #49 — a ratchet over the hospital-scoped patient read surface.
 *
 * <p>The decision record asks for "a guard test that fails when a new
 * patient-scoped query bypasses the filter". A guard that could actually
 * decide correctness would have to know which of ~47 finders SHOULD widen,
 * and that is a per-query clinical judgement, not something a test can
 * derive. So this is the honest version: it freezes the current inventory
 * and fails when it grows.
 *
 * <p>Adding a patient+hospital finder is therefore a deliberate act — you
 * update the list below, which is the moment to decide whether the new query
 * widens across hospitals (route it through
 * {@link RecordAccessPolicy#readableHospitalIds}) or stays acting-hospital
 * only. Silence is what this prevents; it does not prove correctness.
 *
 * <p>Modelled on {@code SchedulerLockCoverageTest}: read the sources rather
 * than the classpath, because Spring Data interfaces carry no runtime marker
 * distinguishing a tenant-scoped finder from any other.
 */
@DisplayName("Cross-hospital read filter coverage")
class CrossHospitalReadFilterCoverageTest {

    private static final Path REPOSITORY_DIR =
        Paths.get("src/main/java/com/example/hms/repository");

    /**
     * Finders actually routed through {@link RecordAccessPolicy#readableHospitalIds}
     * — they take a collection of hospital ids rather than one.
     *
     * <p>The encounter and lab-result paths widen too, but they filter in memory
     * over an already patient-scoped query, so they have no hospital-taking
     * finder to list here. Allergies went patient-wide in E9 #56 (decision D3:
     * untagged travels); the chart domain — problems, surgical history,
     * directives, nursing notes, chart updates — in E9 #59a. Problems and
     * nursing notes carry a sensitivity tag and are filtered through
     * {@code CrossHospitalRows.maySurface}; the rest are untagged and travel.
     */
    private static final Set<String> WIDENED = Set.of(
        "PrescriptionRepository.findByPatient_IdAndHospital_IdIn",
        // E9 #59a — the chart domain follows the patient.
        "PatientProblemRepository.findByPatient_IdAndHospital_IdIn",
        "PatientSurgicalHistoryRepository.findByPatient_IdAndHospital_IdIn",
        "AdvanceDirectiveRepository.findByPatient_IdAndHospital_IdIn",
        "NursingNoteRepository.findTop50ByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        "NursingNoteRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        "PatientChartUpdateRepository.findByPatient_IdAndHospital_IdIn",
        // E9 #59b — orders and results follow the patient.
        "LabOrderRepository.findByPatient_IdAndHospital_IdIn",
        "LabResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn",
        "ImagingOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc",
        "ImagingOrderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc",
        "ProcedureOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc",
        "ConsultationRepository.findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc",
        "GeneralReferralRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        // E9 #59c — medications follow the patient (PrescriptionRepository's In
        // finder, listed above, gained a paged overload).
        "PharmacyFillRepository.findByPatient_IdAndHospital_IdInOrderByFillDateDesc",
        "PharmacyFillRepository.findByPatient_IdAndHospital_IdInAndFillDateBetweenOrderByFillDateDesc",
        // E9 #59d — maternity and immunizations follow the patient.
        "LaborEpisodeRepository.findByPatient_IdAndHospital_IdInOrderByAdmittedAtDesc",
        "NewbornAssessmentRepository.findByPatient_IdAndHospital_IdInOrderByAssessmentTimeDesc",
        "PostpartumObservationRepository.findByPatient_IdAndHospital_IdInOrderByObservationTimeDesc",
        "BirthPlanRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        "BirthPlanRepository.findFirstByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        "HighRiskPregnancyCarePlanRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc",
        "HighRiskPregnancyCarePlanRepository.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc",
        "MaternalHistoryRepository.findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc",
        "MaternalHistoryRepository.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc",
        "ObgynReferralRepository.findByPatient_IdAndHospital_IdIn",
        "UltrasoundOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedDateDesc",
        "UltrasoundOrderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedDateDesc",
        "UltrasoundReportRepository.findByUltrasoundOrder_Patient_IdAndHospital_IdInOrderByScanDateDesc",
        "ImmunizationRepository.findByPatient_IdAndHospital_IdInOrderByAdministrationDateDesc",
        "ImmunizationRepository.findByPatient_IdAndHospital_IdInAndVaccineCodeOrderByAdministrationDateDesc",
        // E9 #59e — discharge and admissions follow the patient.
        "DischargeSummaryRepository.findByPatient_IdAndHospital_IdInOrderByDischargeDateDesc",
        "AdmissionRepository.findByPatient_IdAndHospital_IdInOrderByAdmissionDateTimeDesc",
        "EncounterRepository.findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc",
        // E9 #60 — the whole-chart surfaces.
        "PatientVitalSignRepository.findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc",
        "PatientVitalSignRepository.findPageByPatient_IdAndHospital_IdInOrderByRecordedAtDesc",
        "LabResultRepository.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn",
        "DischargeSummaryRepository.findWithAssociationsByPatient_IdAndHospital_IdInOrderByDischargeDateDesc");

    /**
     * Single-hospital patient finders still declared. Every one of these reads
     * the acting hospital only. Growing this list is fine; doing it without
     * noticing is not — and since the assertion is a ceiling, this number is
     * kept at the EXACT current count so that a removed finder is noticed too.
     * 29 at #49-pass-1; 28 after E9 #59a — the chart domain widened beside the
     * single finders that CDS, FHIR, bulk export and consent sharing still
     * call, and only the chart-update page finder had no caller left.
     */
    private static final int SINGLE_HOSPITAL_FINDER_BUDGET = 28;

    @Test
    @DisplayName("the single-hospital patient-read surface has not grown unnoticed")
    void surfaceHasNotGrown() throws IOException {
        List<String> found = singleHospitalFinders();

        // A floor as well as a ceiling: if the detection ever stops matching
        // (a rename, a formatting change), the count collapses to zero and the
        // ratchet would pass forever while guarding nothing.
        assertThat(found)
            .as("The finder scan matched nothing — the detection is broken, not the surface")
            .isNotEmpty();

        assertThat(found)
            .as("New patient+hospital finders were added:%n%s%n%n"
                    + "Each one is a decision: does it widen across hospitals "
                    + "(take a Collection<UUID> and resolve it through "
                    + "RecordAccessPolicy.readableHospitalIds) or stay acting-hospital "
                    + "only? Make the call, then update SINGLE_HOSPITAL_FINDER_BUDGET.",
                String.join("\n", found))
            .hasSizeLessThanOrEqualTo(SINGLE_HOSPITAL_FINDER_BUDGET);
    }

    @Test
    @DisplayName("every widened finder takes a collection, never a single hospital id")
    void widenedFindersTakeACollection() throws IOException {
        for (String qualified : new TreeSet<>(WIDENED)) {
            String[] parts = qualified.split("\\.");
            Path file = REPOSITORY_DIR.resolve(parts[0] + ".java");
            assertThat(file).as("%s must exist", file).exists();

            String source = Files.readString(file, StandardCharsets.UTF_8);
            int at = source.indexOf(parts[1] + "(");
            assertThat(at).as("%s must declare %s", parts[0], parts[1]).isNotNegative();

            String signature = source.substring(at, Math.min(source.length(), at + 260));
            // A widened finder that quietly took a single UUID would compile,
            // pass every other test, and silently stop widening.
            assertThat(signature)
                .as("%s must accept a Collection of hospital ids", qualified)
                .contains("Collection<UUID>");
        }
    }

    /** Declarations of the shape {@code ...Patient...Hospital_Id(...)} — single-hospital reads. */
    private static List<String> singleHospitalFinders() throws IOException {
        try (Stream<Path> files = Files.walk(REPOSITORY_DIR)) {
            return files
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(CrossHospitalReadFilterCoverageTest::findersIn)
                .sorted()
                .toList();
        }
    }

    private static Stream<String> findersIn(Path file) {
        final String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        String repository = file.getFileName().toString().replace(".java", "");
        return source.lines()
            .map(String::trim)
            .filter(line -> line.contains("Patient") && line.contains("Hospital_Id("))
            .filter(line -> !line.startsWith("//") && !line.startsWith("*"))
            .map(line -> repository + " :: " + line);
    }
}
