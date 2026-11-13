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
import static org.mockito.Mockito.when;

@DisplayName("User decision 2026-11-12: the after-visit summary's content sections")
class AfterVisitSummarySectionsTest {

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

    private final AfterVisitSummarySections sections = new AfterVisitSummarySections(vitals, labOrders, labResults,
        imagingOrders, imagingReports, allergies, prescriptions, admissions, problems, surgeries, families, socials);

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
        sections.fill(encounter, avs);
        return avs;
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
        PatientVitalSign weightOnly = new PatientVitalSign();
        weightOnly.setWeightKg(12.4);
        PatientVitalSign set = new PatientVitalSign();
        set.setTemperatureCelsius(38.6);
        set.setHeartRateBpm(128);
        set.setSpo2Percent(96);
        set.setClinicallySignificant(true);
        when(vitals.findWithinRange(eq(patientId), eq(hospitalId), eq(encounter.getArrivalTimestamp()),
            eq(encounter.getCheckoutTimestamp()), any())).thenReturn(List.of(weightOnly, set));

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
        when(vitals.findWithinRange(eq(patientId), eq(hospitalId), any(), any(), any()))
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

        sections.fill(encounter, avs);

        assertThat(avs.getClinicalAssessment()).as("already the diagnosis: not repeated").isNull();
        assertThat(avs.getClinicalPlan()).isEqualTo("ACT 3 jours, revoir si fièvre");
        assertThat(avs.getFollowUpInstructions()).isEqualTo("Boire beaucoup");
        assertThat(avs.toString()).doesNotContain("DRAFT-ASSESSMENT");
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
                if (type.getSimpleName().matches("(?i).*(proxy|guardian|mother|parent|relationship|link|newborn|"
                        + "relative|kin|household|patientrepository).*")) {
                    related.add(type.getSimpleName());
                }
            }
        }
        assertThat(related).as("the summary reads this patient's chart only").isEmpty();
    }

    @Test
    @DisplayName("withoutParagraph drops only the exact paragraph")
    void withoutParagraph() {
        assertThat(AfterVisitSummarySections.withoutParagraph("A\n\nB", "B")).isEqualTo("A");
        assertThat(AfterVisitSummarySections.withoutParagraph("B", "B")).isNull();
        assertThat(AfterVisitSummarySections.withoutParagraph("AB", "B")).isEqualTo("AB");
        assertThat(AfterVisitSummarySections.withoutParagraph(null, "B")).isNull();
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
