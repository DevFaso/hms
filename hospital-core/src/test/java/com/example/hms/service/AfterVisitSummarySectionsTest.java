package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientFamilyHistory;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.FamilyHistoryRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.SocialHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("User decision 2026-11-12: the after-visit summary's content sections")
class AfterVisitSummarySectionsTest {

    private static final java.util.regex.Pattern RELATED_PATIENT_TYPE = java.util.regex.Pattern.compile(
        "proxy|guardian|mother|parent|relationship|link|newborn|relative|kin|household|patientrepository",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    private final PatientVitalSignRepository vitals = mock(PatientVitalSignRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final LabResultRepository labResults = mock(LabResultRepository.class);
    private final ImagingOrderRepository imagingOrders = mock(ImagingOrderRepository.class);
    private final ImagingReportRepository imagingReports = mock(ImagingReportRepository.class);
    private final PatientAllergyRepository allergies = mock(PatientAllergyRepository.class);
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final PatientProblemRepository problems = mock(PatientProblemRepository.class);
    private final PatientSurgicalHistoryRepository surgeries = mock(PatientSurgicalHistoryRepository.class);
    private final FamilyHistoryRepository families = mock(FamilyHistoryRepository.class);
    private final SocialHistoryRepository socials = mock(SocialHistoryRepository.class);
    private final LabResultMapper labResultMapper = new LabResultMapper();
    private final com.example.hms.repository.AppointmentRepository appointments =
        mock(com.example.hms.repository.AppointmentRepository.class);

    private final AfterVisitSummarySections sections = new AfterVisitSummarySections(vitals, labOrders, labResults,
        imagingOrders, imagingReports, allergies, prescriptions, admissions, problems, surgeries, families, socials,
        labResultMapper, appointments);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("CHU Yalgado");
        hospital.setPhoneNumber("25 30 66 43");
        Department department = new Department();
        department.setName("Pédiatrie");
        department.setPhoneNumber("25 30 66 50");
        Patient patient = new Patient();
        patient.setId(patientId);
        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setPatient(patient);
        encounter.setHospital(hospital);
        encounter.setDepartment(department);
        encounter.setEncounterDate(LocalDateTime.of(2026, 11, 12, 9, 0));
        encounter.setArrivalTimestamp(LocalDateTime.of(2026, 11, 12, 8, 45));
        encounter.setCheckoutTimestamp(LocalDateTime.of(2026, 11, 12, 11, 0));
    }

    private AfterVisitSummaryDTO fill() {
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setDischargeDiagnoses(List.of());
        sections.fill(encounter, avs, false);
        return avs;
    }

    @Test
    @DisplayName("QA D1741: the desk keeps the contact numbers, and no clinical section is read for it")
    void theDeskKeepsTheContactNumbersOnly() {
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        sections.fill(encounter, avs, true);

        assertThat(avs.getHospitalPhone()).isEqualTo("25 30 66 43");
        assertThat(avs.getDepartmentPhone()).isEqualTo("25 30 66 50");
        assertThat(avs.getOrderedTests()).isNull();
        assertThat(avs.getAllergies()).isNull();
        verifyNoInteractions(vitals, labOrders, labResults, imagingOrders, imagingReports, allergies, prescriptions,
            admissions, problems, surgeries, families, socials);
    }

    @Test
    @DisplayName("QA D1740: a released value carries the grade and the range the lab-results page shows")
    void aReleasedValueCarriesItsGradeAndRange() {
        LabOrder order = labOrder("Glycémie à jeun", LabOrderStatus.RESULTED);
        order.getLabTestDefinition().setUnit("g/L");
        order.getLabTestDefinition().setReferenceRanges(new ArrayList<>(List.of(
            LabTestReferenceRange.builder().minValue(0.7).maxValue(0.99).unit("g/L").build())));
        when(labOrders.findByEncounter_Id(encounter.getId())).thenReturn(List.of(order));
        LabResult gly = labResult(order, "1.32", true);
        gly.setResultUnit("g/L");
        when(labResults.findByLabOrder_IdIn(anyCollection())).thenReturn(List.of(gly));

        AfterVisitSummaryDTO.OrderedTest test = fill().getOrderedTests().get(0);

        assertThat(test.getStatus()).isEqualTo("RESULTED");
        assertThat(test.getResults()).singleElement().satisfies(r -> {
            assertThat(r.getAbnormalFlag()).isEqualTo("ABNORMAL_HIGH");
            assertThat(r.getReferenceRange()).isEqualTo("0.7 - 0.99 g/L");
        });
    }

    /** The mapper's severity, in the summary's vocabulary; a value no range grades is not flagged. */
    private static final java.util.Map<String, String> MAPPER_TO_SUMMARY = java.util.Map.of(
        "HIGH", "ABNORMAL_HIGH", "LOW", "ABNORMAL_LOW", "NORMAL", "NORMAL", "CRITICAL", "CRITICAL",
        "ABNORMAL", "ABNORMAL");

    /**
     * QA D1740 guard: for the same row, the summary's flag and range are the
     * ones {@link LabResultMapper} grades on the lab-results page, across the
     * grades (high, low, normal, critical, a positive answer, units that differ).
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "1.32, g/L", "0.5, g/L", "0.8, g/L", "9.9, g/L", "0.1, g/L", "7.2, mmol/L", "Positif, ''"})
    @DisplayName("guard: the summary's flag and range equal the mapper's for the same result")
    void theSummaryGradesAsTheMapperDoes(String value, String unit) {
        LabOrder order = labOrder("Glycémie à jeun", LabOrderStatus.COMPLETED);
        order.getLabTestDefinition().setReferenceRanges(new ArrayList<>(List.of(LabTestReferenceRange.builder()
            .minValue(0.7).maxValue(0.99).criticalLow(0.3).criticalHigh(5.0).unit("g/L").build())));
        when(labOrders.findByEncounter_Id(encounter.getId())).thenReturn(List.of(order));
        LabResult row = labResult(order, value, true);
        row.setResultUnit(unit.isEmpty() ? null : unit);
        when(labResults.findByLabOrder_IdIn(anyCollection())).thenReturn(List.of(row));

        AfterVisitSummaryDTO.TestResult shown = fill().getOrderedTests().get(0).getResults().get(0);

        com.example.hms.payload.dto.LabResultResponseDTO page = labResultMapper.toResponseDTO(row);
        assertThat(shown.getAbnormalFlag()).as("flag of " + value + " " + unit)
            .isEqualTo(MAPPER_TO_SUMMARY.get(page.getSeverityFlag()));
        com.example.hms.payload.dto.LabResultReferenceRangeDTO graded = page.getGradedReferenceRange();
        if (graded == null) {
            assertThat(shown.getReferenceRange()).isNull();
        } else {
            assertThat(shown.getReferenceRange()).startsWith(
                new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT))
                    .format(graded.getMinValue()) + " - ");
        }
    }

    @Test
    @DisplayName("QA D1743: a resulted order with nothing released reads awaiting release, never resulted")
    void aResultedOrderWithNothingReleasedReadsAwaitingRelease() {
        LabOrder resulted = labOrder("Glycémie à jeun", LabOrderStatus.RESULTED);
        LabOrder verified = labOrder("NFS", LabOrderStatus.VERIFIED);
        LabOrder running = labOrder("CRP", LabOrderStatus.IN_PROGRESS);
        when(labOrders.findByEncounter_Id(encounter.getId())).thenReturn(List.of(resulted, verified, running));
        when(labResults.findByLabOrder_IdIn(anyCollection())).thenReturn(List.of(
            labResult(resulted, "SECRET-1.32", false), labResult(verified, "12.1", true)));

        List<AfterVisitSummaryDTO.OrderedTest> tests = fill().getOrderedTests();

        assertThat(tests).extracting(AfterVisitSummaryDTO.OrderedTest::getStatus)
            .containsExactly(AfterVisitSummarySections.LAB_AWAITING_RELEASE, "VERIFIED", "IN_PROGRESS");
        assertThat(tests.get(0).getResults()).isEmpty();
        assertThat(tests.toString()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("with nothing recorded, every section is empty and the contact numbers remain")
    void nothingRecordedLeavesEverySectionEmpty() {
        AfterVisitSummaryDTO avs = fill();

        assertThat(avs.getVitalSigns()).isNull();
        assertThat(avs.getOrderedTests()).isEmpty();
        assertThat(avs.getAllergies()).isEmpty();
        assertThat(avs.getCurrentMedications()).isEmpty();
        assertThat(avs.getClinicalAssessment()).isNull();
        assertThat(avs.getClinicalPlan()).isNull();
        assertThat(avs.getMedicalHistory()).isEmpty();
        assertThat(avs.getSurgicalHistory()).isEmpty();
        assertThat(avs.getFamilyHistory()).isEmpty();
        assertThat(avs.getSocialHistory()).isNull();
        assertThat(avs.getHospitalPhone()).isEqualTo("25 30 66 43");
        assertThat(avs.getDepartmentPhone()).isEqualTo("25 30 66 50");
    }

    @Test
    @DisplayName("the visit's last observation set, read in the visit's window at its hospital, with its flag")
    void vitalSignsOfTheVisit() {
        PatientVitalSign set = new PatientVitalSign();
        set.setTemperatureCelsius(38.6);
        set.setHeartRateBpm(128);
        set.setSpo2Percent(96);
        set.setClinicallySignificant(true);
        when(vitals.findObservationSetsWithinRange(eq(patientId), eq(hospitalId), eq(encounter.getArrivalTimestamp()),
            eq(encounter.getCheckoutTimestamp()), any())).thenReturn(List.of(set));

        AfterVisitSummaryDTO.VitalSignsResponse shown = fill().getVitalSigns();

        assertThat(shown.getTemperatureCelsius()).isEqualTo(38.6);
        assertThat(shown.getHeartRateBpm()).isEqualTo(128);
        assertThat(shown.isFlagged()).isTrue();
    }

    @Test
    void lateArrivalExcludesEarlierReadings() {
        encounter.setArrivalTimestamp(encounter.getEncounterDate().plusMinutes(30));
        PatientVitalSign earlier = new PatientVitalSign();
        earlier.setRecordedAt(encounter.getEncounterDate().plusMinutes(15));
        earlier.setTemperatureCelsius(38.6);
        when(vitals.findObservationSetsWithinRange(eq(patientId), eq(hospitalId), any(), any(), any()))
            .thenAnswer(invocation -> {
                LocalDateTime from = invocation.getArgument(2);
                return from.isAfter(earlier.getRecordedAt()) ? List.of() : List.of(earlier);
            });

        assertThat(fill().getVitalSigns()).isNull();
    }

    @Test
    void missingArrivalFallsBackToEncounterDate() {
        encounter.setArrivalTimestamp(null);
        fill();
        verify(vitals).findWithinRange(eq(patientId), eq(hospitalId), eq(encounter.getEncounterDate()),
            eq(encounter.getCheckoutTimestamp()), any());
    }

    @Test
    void isolatedMeasurementIsUsedOnlyWhenNoObservationSetExists() {
        PatientVitalSign glucose = new PatientVitalSign();
        glucose.setBloodGlucoseMgDl(110);
        when(vitals.findWithinRange(eq(patientId), eq(hospitalId), eq(encounter.getArrivalTimestamp()),
            eq(encounter.getCheckoutTimestamp()), any())).thenReturn(List.of(glucose));

        assertThat(fill().getVitalSigns().getBloodGlucoseMgDl()).isEqualTo(110);
        verify(vitals).findObservationSetsWithinRange(eq(patientId), eq(hospitalId),
            eq(encounter.getArrivalTimestamp()), eq(encounter.getCheckoutTimestamp()), any());
    }

    @Test
    @DisplayName("a lab value shows once released, never before; a voided order is left out")
    void unreleasedLabValuesAreNotShown() {
        LabOrder released = labOrder("NFS", LabOrderStatus.COMPLETED);
        LabOrder pending = labOrder("Glycémie", LabOrderStatus.IN_PROGRESS);
        LabOrder voided = labOrder("Wrong patient", LabOrderStatus.ENTERED_IN_ERROR);
        when(labOrders.findByEncounter_Id(encounter.getId())).thenReturn(List.of(released, pending, voided));
        LabResult shown = labResult(released, "11.2", true);
        shown.setAbnormalFlag(AbnormalFlag.ABNORMAL_LOW);
        LabResult hidden = labResult(pending, "SECRET-VALUE", false);
        when(labResults.findByLabOrder_IdIn(anyCollection())).thenReturn(List.of(shown, hidden));

        List<AfterVisitSummaryDTO.OrderedTest> tests = fill().getOrderedTests();

        assertThat(tests).extracting(AfterVisitSummaryDTO.OrderedTest::getName).containsExactly("NFS", "Glycémie");
        assertThat(tests.get(0).getResults()).singleElement()
            .satisfies(r -> {
                assertThat(r.getValue()).isEqualTo("11.2");
                assertThat(r.getAbnormalFlag()).isEqualTo("ABNORMAL_LOW");
            });
        assertThat(tests.get(1).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(tests.get(1).getResults()).isEmpty();
        assertThat(tests.toString()).doesNotContain("SECRET-VALUE").doesNotContain("Wrong patient");
    }

    @Test
    @DisplayName("an imaging impression shows once the report is signed; a cancelled order carries no reason")
    void imagingImpressionOnlyOnceSigned() {
        ImagingOrder signedOrder = imagingOrder("Radio thorax", ImagingOrderStatus.RESULTS_AVAILABLE);
        ImagingOrder preliminaryOrder = imagingOrder("Échographie abdominale", ImagingOrderStatus.IN_PROGRESS);
        ImagingOrder cancelled = imagingOrder("Scanner", ImagingOrderStatus.CANCELLED);
        cancelled.setCancellationReason("INTERNAL-REASON");
        when(imagingOrders.findByEncounterIdAndStatusNotIn(eq(encounter.getId()), any()))
            .thenReturn(List.of(signedOrder, preliminaryOrder, cancelled));
        when(imagingReports.findByImagingOrder_IdInAndLatestVersionIsTrue(any())).thenReturn(List.of(
            imagingReport(signedOrder, "Pas de foyer", LocalDateTime.now()),
            imagingReport(preliminaryOrder, "UNSIGNED-READ", null)));

        List<AfterVisitSummaryDTO.OrderedTest> tests = fill().getOrderedTests();

        assertThat(tests).extracting(AfterVisitSummaryDTO.OrderedTest::getImpression)
            .containsExactly("Pas de foyer", null, null);
        assertThat(tests.get(2).getStatus()).isEqualTo("CANCELLED");
        assertThat(tests.toString()).doesNotContain("UNSIGNED-READ").doesNotContain("INTERNAL-REASON");
    }

    @Test
    @DisplayName("current medicines: a signed course shows; a draft, a cancelled draft and a stopped order do not")
    void draftsAndCancelledMedicationsAreNotShown() {
        Prescription running = prescription("Amoxicilline", PrescriptionStatus.SIGNED);
        Prescription draft = prescription("DRAFT-MED", PrescriptionStatus.DRAFT);
        Prescription withdrawnDraft = prescription("WITHDRAWN-DRAFT", PrescriptionStatus.DRAFT);
        withdrawnDraft.setStatus(PrescriptionStatus.CANCELLED);
        Prescription stopped = prescription("STOPPED-MED", PrescriptionStatus.SIGNED);
        stopped.setStatus(PrescriptionStatus.DISCONTINUED);
        Prescription unsigned = prescription("UNSIGNED-MED", PrescriptionStatus.PENDING_SIGNATURE);
        when(prescriptions.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(running, draft, withdrawnDraft, stopped, unsigned));

        assertThat(fill().getCurrentMedications()).extracting(AfterVisitSummaryDTO.CurrentMedication::getName)
            .containsExactly("Amoxicilline");
    }

    @Test
    @DisplayName("allergies: active ones with their severity; a refuted, an erroneous or a deactivated one is left out")
    void allergiesWithSeverity() {
        PatientAllergy penicillin = allergy("Pénicilline", AllergySeverity.SEVERE, AllergyVerificationStatus.CONFIRMED, true);
        PatientAllergy refuted = allergy("REFUTED", AllergySeverity.MILD, AllergyVerificationStatus.REFUTED, true);
        PatientAllergy error = allergy("ERROR", AllergySeverity.MILD, AllergyVerificationStatus.ENTERED_IN_ERROR, true);
        PatientAllergy inactive = allergy("INACTIVE", AllergySeverity.MILD, AllergyVerificationStatus.CONFIRMED, false);
        when(allergies.findVisibleAt(patientId, hospitalId)).thenReturn(List.of(penicillin, refuted, error, inactive));

        assertThat(fill().getAllergies()).singleElement().satisfies(a -> {
            assertThat(a.getAllergen()).isEqualTo("Pénicilline");
            assertThat(a.getSeverity()).isEqualTo("SEVERE");
        });
    }

    @Test
    @DisplayName("the signed note's A and P under their own labels; the plan leaves the follow-up; a draft adds nothing")
    void assessmentAndPlanFromTheSignedNote() {
        EncounterNote draft = new EncounterNote();
        draft.setAssessment("DRAFT-ASSESSMENT");
        draft.setCreatedAt(LocalDateTime.now());
        EncounterNote signed = new EncounterNote();
        signed.setAssessment("Paludisme simple");
        signed.setPlan("ACT 3 jours, revoir si fièvre");
        signed.setSignedAt(LocalDateTime.now().minusHours(1));
        signed.setCreatedAt(LocalDateTime.now().minusHours(2));
        encounter.setEncounterNote(signed);
        encounter.setEncounterNote(draft);
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setDischargeDiagnoses(List.of("Paludisme simple"));
        avs.setFollowUpInstructions("Boire beaucoup\n\nACT 3 jours, revoir si fièvre");

        sections.fill(encounter, avs, false);

        assertThat(avs.getClinicalAssessment()).as("already the diagnosis: not repeated").isNull();
        assertThat(avs.getClinicalPlan()).isEqualTo("ACT 3 jours, revoir si fièvre");
        assertThat(avs.getFollowUpInstructions()).isEqualTo("Boire beaucoup");
        assertThat(avs.toString()).doesNotContain("DRAFT-ASSESSMENT");
    }

    @Test
    void multiParagraphPlanAppendedByTheMapperIsShownOnlyInItsOwnSection() {
        EncounterNote signed = new EncounterNote();
        signed.setPlan("First plan paragraph\n\nSecond plan paragraph");
        signed.setPatientInstructions("Keep these instructions");
        signed.setSignedAt(LocalDateTime.now());
        signed.setCreatedAt(LocalDateTime.now().minusHours(1));
        encounter.setEncounterNote(signed);
        AfterVisitSummaryDTO avs = new com.example.hms.mapper.CheckOutMapper()
            .toAfterVisitSummary(encounter, null, null);

        sections.fill(encounter, avs, false);

        assertThat(avs.getFollowUpInstructions()).isEqualTo("Keep these instructions");
        assertThat(avs.getClinicalPlan()).isEqualTo(signed.getPlan());
    }

    @Test
    @DisplayName("histories: this patient's own, a removed diagnosis and a corrected family entry left out")
    void historiesOfThePatient() {
        PatientProblem asthma = new PatientProblem();
        asthma.setProblemDisplay("Asthme");
        asthma.setStatus(ProblemStatus.ACTIVE);
        PatientProblem removed = new PatientProblem();
        removed.setProblemDisplay("REMOVED-DX");
        removed.setEnteredInError(true);
        when(problems.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(asthma, removed));
        PatientSurgicalHistory appendix = new PatientSurgicalHistory();
        appendix.setProcedureDisplay("Appendicectomie");
        appendix.setProcedureDate(LocalDate.of(2020, 3, 1));
        when(surgeries.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(appendix));
        PatientFamilyHistory mother = family("Mère", "Drépanocytose", true);
        PatientFamilyHistory corrected = family("Père", "CORRECTED", false);
        when(families.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(patientId, hospitalId))
            .thenReturn(List.of(mother, corrected));
        PatientSocialHistory social = new PatientSocialHistory();
        social.setTobaccoUse(false);
        social.setOccupation("Élève");
        social.setActive(true);
        when(socials.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(patientId, hospitalId))
            .thenReturn(List.of(social));

        AfterVisitSummaryDTO avs = fill();

        assertThat(avs.getMedicalHistory()).extracting(AfterVisitSummaryDTO.MedicalHistoryEntry::getCondition)
            .containsExactly("Asthme");
        assertThat(avs.getSurgicalHistory()).extracting(AfterVisitSummaryDTO.SurgicalHistoryEntry::getProcedure)
            .containsExactly("Appendicectomie");
        assertThat(avs.getFamilyHistory()).extracting(AfterVisitSummaryDTO.FamilyHistoryEntry::getCondition)
            .containsExactly("Drépanocytose");
        assertThat(avs.getSocialHistory().getOccupation()).isEqualTo("Élève");
    }

    /**
     * Privacy: a child's summary shows the family history recorded in the
     * CHILD's chart, and nothing from a parent's own chart, which is another
     * patient's record. Every read is stubbed to answer the parent's rows for
     * the parent's id; none of them may reach the child's summary.
     */
    @Test
    @DisplayName("a child's summary holds no row of the parent's own chart")
    void aChildsSummaryHoldsNothingFromTheParentsChart() {
        UUID parentId = UUID.randomUUID();
        PatientFamilyHistory recordedForChild = family("Mère", "Drépanocytose (mère)", true);
        when(families.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(patientId, hospitalId))
            .thenReturn(List.of(recordedForChild));
        // The parent's own chart.
        PatientProblem parentDiagnosis = new PatientProblem();
        parentDiagnosis.setProblemDisplay("PARENT-HIV");
        when(problems.findByPatient_IdAndHospital_Id(eq(parentId), any())).thenReturn(List.of(parentDiagnosis));
        when(families.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(eq(parentId), any()))
            .thenReturn(List.of(family("Frère", "PARENT-FAMILY", true)));
        when(allergies.findVisibleAt(eq(parentId), any()))
            .thenReturn(List.of(allergy("PARENT-ALLERGY", AllergySeverity.MILD, AllergyVerificationStatus.CONFIRMED, true)));
        when(prescriptions.findByPatient_IdAndHospital_Id(eq(parentId), any()))
            .thenReturn(List.of(prescription("PARENT-ARV", PrescriptionStatus.SIGNED)));

        AfterVisitSummaryDTO avs = fill();

        assertThat(avs.getFamilyHistory()).extracting(AfterVisitSummaryDTO.FamilyHistoryEntry::getCondition)
            .containsExactly("Drépanocytose (mère)");
        assertThat(avs.toString()).doesNotContain("PARENT-");
        verify(problems, never()).findByPatient_IdAndHospital_Id(eq(parentId), any());
        verify(families, never()).findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(eq(parentId), any());
        verify(allergies, never()).findVisibleAt(eq(parentId), any());
        verify(prescriptions, never()).findByPatient_IdAndHospital_Id(eq(parentId), any());
        verify(socials, never()).findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(eq(parentId), any());
        verify(surgeries, never()).findByPatient_IdAndHospital_Id(eq(parentId), any());
    }

    /**
     * Guard for the same rule: the class reads only the visit's own rows, by
     * the encounter's own patient. A dependency that could resolve a related
     * patient (a proxy, a guardian, a mother, a family link) is refused here,
     * so adding one is a decision, not a side effect.
     */
    @Test
    @DisplayName("guard: the sections depend on no repository that resolves a related patient")
    void theSectionsCannotReachARelatedPatient() {
        Constructor<?>[] constructors = AfterVisitSummarySections.class.getConstructors();
        List<String> related = new ArrayList<>();
        for (Constructor<?> constructor : constructors) {
            for (Class<?> type : constructor.getParameterTypes()) {
                if (RELATED_PATIENT_TYPE.matcher(type.getSimpleName()).find()) {
                    related.add(type.getSimpleName());
                }
            }
        }
        assertThat(related).as("the summary reads this patient's chart only").isEmpty();
    }

    /* ── Polish 2026-11-13 ── */

    @Test
    @DisplayName("polish: each value the vital-sign rules judge is flagged; 142/88 and an unjudged weight are not")
    void vitalValuesAreFlaggedOnlyByTheExistingRules() {
        PatientVitalSign set = new PatientVitalSign();
        set.setSystolicBpMmHg(142);
        set.setDiastolicBpMmHg(88);
        set.setHeartRateBpm(72);
        set.setSpo2Percent(86);
        set.setWeightKg(70.0);
        set.setHeightCm(165.0);
        when(vitals.findObservationSetsWithinRange(eq(patientId), eq(hospitalId), any(), any(), any()))
            .thenReturn(List.of(set));

        AfterVisitSummaryDTO.VitalSignsResponse shown = fill().getVitalSigns();

        assertThat(shown.getValueFlags()).containsExactly(java.util.Map.entry("spo2Percent", "LOW"));
        assertThat(shown.getBmi()).isEqualTo(25.7);
    }

    @Test
    @DisplayName("polish: no BMI without both a weight and a height")
    void noBmiWithoutBothMeasures() {
        assertThat(AfterVisitSummarySections.bmi(70.0, null)).isNull();
        assertThat(AfterVisitSummarySections.bmi(null, 165.0)).isNull();
        assertThat(AfterVisitSummarySections.bmi(70.0, 165.0)).isEqualTo(25.7);
    }

    @Test
    @DisplayName("polish: each medicine carries its start date and its prescriber")
    void medicinesCarryTheirStartAndPrescriber() {
        Prescription rx = prescription("Amlodipine", PrescriptionStatus.SIGNED);
        rx.setSignedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        com.example.hms.model.Staff signer = new com.example.hms.model.Staff();
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setFirstName("Issa");
        user.setLastName("Kaboré");
        signer.setUser(user);
        rx.setSignedBy(signer);
        when(prescriptions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(rx));

        AfterVisitSummaryDTO.CurrentMedication shown = fill().getCurrentMedications().get(0);

        assertThat(shown.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(shown.getPrescriberName()).isEqualTo("Issa Kaboré");
    }

    /**
     * User decision 2026-11-13 (option a): the active lines of one drug share a
     * key, by the catalogue item or by the name in OrderedDrugIdentity's
     * spelling form with the strength left out; two different drugs do not.
     */
    @Test
    @DisplayName("option a: three active paracetamol orders share one drug key; two different drugs do not")
    void activeLinesOfOneDrugShareAKey() {
        UUID paracetamolItem = UUID.randomUUID();
        Prescription plain = prescription("Paracétamol", PrescriptionStatus.SIGNED);
        Prescription withStrength = prescription("PARACETAMOL 500 mg", PrescriptionStatus.SIGNED);
        Prescription brand = prescription("Doliprane", PrescriptionStatus.SIGNED);
        brand.setMedicationCatalogItemId(paracetamolItem);
        withStrength.setMedicationCatalogItemId(paracetamolItem);
        Prescription amlodipine = prescription("Amlodipine 5 mg", PrescriptionStatus.SIGNED);
        Prescription metformin = prescription("Metformine", PrescriptionStatus.SIGNED);

        List<String> keys = AfterVisitSummarySections.drugKeys(
            List.of(plain, withStrength, brand, amlodipine, metformin));

        assertThat(keys.get(0)).isEqualTo(keys.get(1)).isEqualTo(keys.get(2));
        assertThat(keys.get(3)).isNotEqualTo(keys.get(0)).isNotEqualTo(keys.get(4));
        assertThat(keys.get(4)).isNotEqualTo(keys.get(0));
    }

    @Test
    @DisplayName("option a: a combination is not the drug alone")
    void aCombinationIsNotTheDrugAlone() {
        List<String> keys = AfterVisitSummarySections.drugKeys(List.of(
            prescription("Paracétamol", PrescriptionStatus.SIGNED),
            prescription("Paracétamol + codéine", PrescriptionStatus.SIGNED)));
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
    }

    @Test
    @DisplayName("polish: the follow-up booked at check-out shows its time, service and clinician")
    void theBookedFollowUpShowsItsTimeServiceAndClinician() {
        com.example.hms.model.Appointment booked = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.PENDING);
        when(appointments.findById(booked.getId())).thenReturn(java.util.Optional.of(booked));
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setFollowUpAppointmentId(booked.getId());

        sections.fill(encounter, avs, true);

        assertThat(avs.getFollowUpAppointmentDate()).isEqualTo("2026-12-10");
        assertThat(avs.getFollowUpAppointmentTime()).isEqualTo("09:30");
        assertThat(avs.getFollowUpDepartmentName()).isEqualTo("Cardiologie");
        assertThat(avs.getFollowUpClinicianName()).isEqualTo("Issa Kaboré");
    }

    @Test
    @DisplayName("polish: a later read finds the booking on the follow-up day; another hospital's or a cancelled one is not shown")
    void aLaterReadFindsOnlyThisVisitsLiveBooking() {
        com.example.hms.model.Appointment elsewhere = followUp(patientId, UUID.randomUUID(),
            com.example.hms.enums.AppointmentStatus.PENDING);
        com.example.hms.model.Appointment cancelled = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.CANCELLED);
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(patientId, hospitalId,
            LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 10))).thenReturn(List.of(elsewhere));
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setFollowUpAppointmentDate("2026-12-10");
        sections.fill(encounter, avs, true);
        assertThat(avs.getFollowUpAppointmentTime()).isNull();

        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(patientId, hospitalId,
            LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 10))).thenReturn(List.of(cancelled));
        sections.fill(encounter, avs, true);
        assertThat(avs.getFollowUpAppointmentTime()).isNull();

        com.example.hms.model.Appointment live = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.SCHEDULED);
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(patientId, hospitalId,
            LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 10))).thenReturn(List.of(live));
        sections.fill(encounter, avs, true);
        assertThat(avs.getFollowUpAppointmentTime()).isEqualTo("09:30");
    }

    @Test
    @DisplayName("Rex P2 #1145: a cancelled booking never hides a live one; the earliest live start is shown")
    void aCancelledBookingNeverHidesALiveOne() {
        com.example.hms.model.Appointment cancelled = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.CANCELLED);
        cancelled.setStartTime(java.time.LocalTime.of(8, 0));
        com.example.hms.model.Appointment later = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.SCHEDULED);
        later.setStartTime(java.time.LocalTime.of(11, 0));
        com.example.hms.model.Appointment nearest = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.PENDING);
        nearest.setStartTime(java.time.LocalTime.of(9, 30));
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(patientId, hospitalId,
            LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 10))).thenReturn(List.of(cancelled, later, nearest));
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setFollowUpAppointmentDate("2026-12-10");

        sections.fill(encounter, avs, true);

        assertThat(avs.getFollowUpAppointmentTime()).isEqualTo("09:30");
    }

    @Test
    @DisplayName("Rex P2 #1145: a booking cancelled since the check-out shows no time, service or clinician")
    void aCancelledBookingByIdShowsNothing() {
        com.example.hms.model.Appointment cancelled = followUp(patientId, hospitalId,
            com.example.hms.enums.AppointmentStatus.CANCELLED);
        when(appointments.findById(cancelled.getId())).thenReturn(java.util.Optional.of(cancelled));
        AfterVisitSummaryDTO avs = new AfterVisitSummaryDTO();
        avs.setFollowUpAppointmentId(cancelled.getId());

        sections.fill(encounter, avs, true);

        assertThat(avs.getFollowUpAppointmentTime()).isNull();
        assertThat(avs.getFollowUpClinicianName()).isNull();
    }

    private com.example.hms.model.Appointment followUp(UUID patient, UUID hospital,
                                                       com.example.hms.enums.AppointmentStatus status) {
        Patient p = new Patient();
        p.setId(patient);
        Hospital h = new Hospital();
        h.setId(hospital);
        Department cardiology = new Department();
        cardiology.setName("Cardiologie");
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setFirstName("Issa");
        user.setLastName("Kaboré");
        staff.setUser(user);
        com.example.hms.model.Appointment a = new com.example.hms.model.Appointment();
        a.setId(UUID.randomUUID());
        a.setPatient(p);
        a.setHospital(h);
        a.setDepartment(cardiology);
        a.setStaff(staff);
        a.setStatus(status);
        a.setAppointmentDate(LocalDate.of(2026, 12, 10));
        a.setStartTime(java.time.LocalTime.of(9, 30));
        a.setCreatedAt(encounter.getCheckoutTimestamp().plusMinutes(1));
        return a;
    }

    @Test
    @DisplayName("withoutPlanSuffix drops only the complete appended plan")
    void withoutPlanSuffix() {
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("A\n\nB", "B")).isEqualTo("A");
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("B", "B")).isNull();
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("AB", "B")).isEqualTo("AB");
        assertThat(AfterVisitSummarySections.withoutPlanSuffix(null, "B")).isNull();
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("A\n\nB\n\nC", "B\n\nC")).isEqualTo("A");
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("B\n\nC", "B\n\nC")).isNull();
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("B\n\nA", "B")).isEqualTo("B\n\nA");
        assertThat(AfterVisitSummarySections.withoutPlanSuffix("A\n\nB\n\nC", "B")).isEqualTo("A\n\nB\n\nC");
    }

    private static LabOrder labOrder(String name, LabOrderStatus status) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName(name);
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(definition);
        order.setStatus(status);
        return order;
    }

    private static LabResult labResult(LabOrder order, String value, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit("g/dL");
        result.setReleased(released);
        return result;
    }

    private static ImagingOrder imagingOrder(String study, ImagingOrderStatus status) {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setStudyType(study);
        order.setStatus(status);
        return order;
    }

    private static ImagingReport imagingReport(ImagingOrder order, String impression, LocalDateTime signedAt) {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(order);
        report.setImpression(impression);
        report.setSignedAt(signedAt);
        report.setReportStatus(signedAt != null ? ImagingReportStatus.FINAL : ImagingReportStatus.PRELIMINARY);
        return report;
    }

    private static Prescription prescription(String name, PrescriptionStatus status) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName(name);
        rx.setDosage("500 mg");
        rx.setFrequency("3 fois par jour");
        rx.setStatus(status);
        return rx;
    }

    private static PatientAllergy allergy(String name, AllergySeverity severity, AllergyVerificationStatus verification,
                                          boolean active) {
        PatientAllergy allergy = new PatientAllergy();
        allergy.setAllergenDisplay(name);
        allergy.setSeverity(severity);
        allergy.setVerificationStatus(verification);
        allergy.setActive(active);
        return allergy;
    }

    private static PatientFamilyHistory family(String relationship, String condition, boolean active) {
        PatientFamilyHistory entry = new PatientFamilyHistory();
        entry.setRelationship(relationship);
        entry.setConditionDisplay(condition);
        entry.setActive(active);
        return entry;
    }
}
