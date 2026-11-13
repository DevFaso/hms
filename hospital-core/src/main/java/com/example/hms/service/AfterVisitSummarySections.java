package com.example.hms.service;

import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.mapper.LabResultMapper;
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
import com.example.hms.service.lab.ClinicalLabResultView;
import com.example.hms.service.lab.LabResultCorrections;
import com.example.hms.service.support.PrescriptionIssuance;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User decision 2026-11-12: the after-visit summary ("Résumé de visite") also
 * carries the visit's vital signs, the tests ordered, the allergies, the
 * medicines in force, the signed note's assessment and plan, whom to call, and
 * the patient's recorded history. Each section is left empty when there is
 * nothing to show.
 *
 * <p>What each section may show:
 * <ul>
 *   <li><strong>One hospital.</strong> Every row is read at the visit's own
 *       hospital, so a summary never discloses another hospital's record (the
 *       shared-patient reads of #1103 stay on the chart, where they are
 *       audited). The one exception is the allergies, which follow the patient
 *       across hospitals by policy (E9 #56) unless the patient opted out
 *       ({@code findVisibleAt}).</li>
 *   <li><strong>One patient.</strong> Every read is keyed by the encounter's
 *       own patient id. A child's summary shows the family history recorded in
 *       the CHILD's chart; a parent's own chart is another patient's record,
 *       and reading it here would be a privacy breach, so nothing in this class
 *       resolves a parent, a guardian, a proxy or any other related patient.
 *       {@code AfterVisitSummarySectionsTest} holds that.</li>
 *   <li><strong>Released results only.</strong> A lab value appears once the
 *       laboratory released it ({@link ClinicalLabResultView}); an imaging
 *       impression once the report is signed ({@code isReleasedToPatient}).
 *       A voided lab order is left out, and no cancellation or void reason is
 *       ever carried.</li>
 *   <li><strong>Signed text only.</strong> The assessment and the plan come
 *       from the latest signed note, as the rest of the summary does; an
 *       unsigned draft adds nothing.</li>
 *   <li><strong>Not for the desk.</strong> The caller skips this class for a
 *       receptionist, and {@code ReceptionistResponseShaping} removes every
 *       clinical field of it besides.</li>
 * </ul>
 *
 * <p>No read here is wrapped in a catch: the summary is built inside the
 * caller's transaction (the check-out writes in it), and a swallowed repository
 * failure would mark that transaction rollback-only (QA D1212).
 */
@Component
@RequiredArgsConstructor
public class AfterVisitSummarySections {

    private static final String LAB = "LAB";
    private static final String IMAGING = "IMAGING";

    private final PatientVitalSignRepository vitalSignRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ImagingReportRepository imagingReportRepository;
    private final PatientAllergyRepository allergyRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AdmissionRepository admissionRepository;
    private final PatientProblemRepository problemRepository;
    private final PatientSurgicalHistoryRepository surgicalHistoryRepository;
    private final FamilyHistoryRepository familyHistoryRepository;
    private final SocialHistoryRepository socialHistoryRepository;

    /** Fills the content sections of {@code avs} from {@code encounter}, its patient and its hospital. */
    public void fill(Encounter encounter, AfterVisitSummaryDTO avs) {
        fillContact(encounter, avs);
        fillNote(encounter.getLatestSignedEncounterNote(), avs);
        // The encounter's own patient and hospital: never a related patient's id (see the class comment).
        UUID patientId = encounter.getPatient() != null ? encounter.getPatient().getId() : null;
        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (patientId == null || hospitalId == null) {
            return;
        }
        avs.setVitalSigns(vitalSigns(encounter, patientId, hospitalId));
        List<AfterVisitSummaryDTO.OrderedTest> tests = new ArrayList<>(labTests(encounter.getId()));
        tests.addAll(imagingTests(encounter.getId()));
        avs.setOrderedTests(tests);
        avs.setAllergies(allergies(patientId, hospitalId));
        avs.setCurrentMedications(currentMedications(patientId, hospitalId));
        avs.setMedicalHistory(medicalHistory(patientId, hospitalId));
        avs.setSurgicalHistory(surgicalHistory(patientId, hospitalId));
        avs.setFamilyHistory(familyHistory(patientId, hospitalId));
        avs.setSocialHistory(socialHistory(patientId, hospitalId));
    }

    private static void fillContact(Encounter encounter, AfterVisitSummaryDTO avs) {
        Hospital hospital = encounter.getHospital();
        if (hospital != null) {
            avs.setHospitalPhone(trimToNull(hospital.getPhoneNumber()));
        }
        Department department = encounter.getDepartment();
        if (department != null) {
            avs.setDepartmentPhone(trimToNull(department.getPhoneNumber()));
        }
    }

    /**
     * The signed note's A and P under their own labels. The plan used to be
     * appended to the follow-up instructions (QA D730); now that it has its own
     * section it is taken out of them, so the patient does not read it twice.
     * An assessment the summary already shows as its diagnosis is not repeated.
     */
    private static void fillNote(EncounterNote note, AfterVisitSummaryDTO avs) {
        if (note == null || note.getSignedAt() == null) {
            return;
        }
        String assessment = trimToNull(note.getAssessment());
        List<String> diagnoses = avs.getDischargeDiagnoses();
        boolean shownAsDiagnosis = assessment != null && diagnoses != null && diagnoses.size() == 1
            && assessment.equals(trimToNull(diagnoses.get(0)));
        avs.setClinicalAssessment(shownAsDiagnosis ? null : assessment);
        String plan = trimToNull(note.getPlan());
        avs.setClinicalPlan(plan);
        if (plan != null) {
            avs.setFollowUpInstructions(withoutPlanSuffix(avs.getFollowUpInstructions(), plan));
        }
    }

    /** Remove only the complete plan appended by CheckOutMapper, including multi-paragraph plans. */
    static String withoutPlanSuffix(String text, String plan) {
        if (text == null) {
            return null;
        }
        String kept = text.trim();
        if (kept.equals(plan)) {
            return null;
        }
        String suffix = "\n\n" + plan;
        if (kept.endsWith(suffix)) {
            kept = kept.substring(0, kept.length() - suffix.length());
        }
        return trimToNull(kept);
    }

    /**
     * The last observation set recorded between the arrival and the check-out,
     * at the visit's hospital. A weight or a pain score alone is taken only when
     * the window holds no observation set.
     */
    private AfterVisitSummaryDTO.VitalSignsResponse vitalSigns(Encounter encounter, UUID patientId, UUID hospitalId) {
        LocalDateTime from = encounter.getArrivalTimestamp() != null
            ? encounter.getArrivalTimestamp() : encounter.getEncounterDate();
        if (from == null) {
            return null;
        }
        // Filter in the query: recent isolated measurements must not push the last set off a page.
        PatientVitalSign chosen = vitalSignRepository.findObservationSetsWithinRange(patientId, hospitalId, from,
                encounter.getCheckoutTimestamp(), PageRequest.of(0, 1)).stream().findFirst()
            .orElseGet(() -> vitalSignRepository.findWithinRange(patientId, hospitalId, from,
                encounter.getCheckoutTimestamp(), PageRequest.of(0, 1)).stream().findFirst().orElse(null));
        if (chosen == null) {
            return null;
        }
        return AfterVisitSummaryDTO.VitalSignsResponse.builder()
            .recordedAt(chosen.getRecordedAt())
            .temperatureCelsius(chosen.getTemperatureCelsius())
            .heartRateBpm(chosen.getHeartRateBpm())
            .respiratoryRateBpm(chosen.getRespiratoryRateBpm())
            .systolicBpMmHg(chosen.getSystolicBpMmHg())
            .diastolicBpMmHg(chosen.getDiastolicBpMmHg())
            .spo2Percent(chosen.getSpo2Percent())
            .bloodGlucoseMgDl(chosen.getBloodGlucoseMgDl())
            .weightKg(chosen.getWeightKg())
            .heightCm(chosen.getHeightCm())
            .painScore(chosen.getPainScore())
            .flagged(chosen.isClinicallySignificant())
            .build();
    }

    /**
     * The visit's lab orders, a voided one left out (it was entered in error,
     * and its reason is internal). A value only once released.
     */
    private List<AfterVisitSummaryDTO.OrderedTest> labTests(UUID encounterId) {
        List<LabOrder> orders = labOrderRepository.findByEncounter_Id(encounterId).stream()
            .filter(o -> o.getStatus() != LabOrderStatus.ENTERED_IN_ERROR)
            .toList();
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = orders.stream().map(LabOrder::getId).collect(Collectors.toSet());
        Map<UUID, List<LabResult>> released = ClinicalLabResultView.releasedAndCurrent(
                labResultRepository.findByLabOrder_IdIn(ids)).stream()
            .filter(ClinicalLabResultView::showsValue)
            .filter(r -> r.getLabOrder() != null)
            .collect(Collectors.groupingBy(r -> r.getLabOrder().getId()));
        return orders.stream()
            .map(order -> AfterVisitSummaryDTO.OrderedTest.builder()
                .kind(LAB)
                .name(order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : null)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .results(released.getOrDefault(order.getId(), List.of()).stream()
                    .map(r -> AfterVisitSummaryDTO.TestResult.builder()
                        .value(r.getResultValue())
                        // QA D1095: the test definition's unit when none was typed.
                        .unit(LabResultMapper.unitOf(r))
                        .referenceRange(r.getReferenceRange())
                        .abnormalFlag(r.getAbnormalFlag() != null ? r.getAbnormalFlag().name() : null)
                        // QA D1016: a released correction says so.
                        .corrected(LabResultCorrections.isCorrection(r))
                        .build())
                    .toList())
                .build())
            .toList();
    }

    /** The visit's imaging orders, a draft left out; the impression only once the report is signed. */
    private List<AfterVisitSummaryDTO.OrderedTest> imagingTests(UUID encounterId) {
        List<ImagingOrder> orders = imagingOrderRepository.findByEncounterIdAndStatusNotIn(encounterId,
            List.of(ImagingOrderStatus.DRAFT));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<UUID, ImagingReport> reports = imagingReportRepository.findByImagingOrder_IdInAndLatestVersionIsTrue(
                orders.stream().map(ImagingOrder::getId).toList()).stream()
            .filter(ImagingReport::isReleasedToPatient)
            .filter(r -> r.getImagingOrder() != null)
            .collect(Collectors.toMap(r -> r.getImagingOrder().getId(), r -> r, (a, b) -> a));
        return orders.stream()
            .map(order -> {
                ImagingReport report = reports.get(order.getId());
                return AfterVisitSummaryDTO.OrderedTest.builder()
                    .kind(IMAGING)
                    .name(firstNonBlank(order.getStudyType(),
                        order.getModality() != null ? order.getModality().name() : null))
                    .status(order.getStatus() != null ? order.getStatus().name() : null)
                    .results(List.of())
                    .impression(report != null ? trimToNull(report.getImpression()) : null)
                    .build();
            })
            .toList();
    }

    /** Active allergies the patient is known to have: a refuted or erroneous entry is not one. */
    private List<AfterVisitSummaryDTO.Allergy> allergies(UUID patientId, UUID hospitalId) {
        return allergyRepository.findVisibleAt(patientId, hospitalId).stream()
            .filter(PatientAllergy::isActive)
            .filter(a -> a.getVerificationStatus() != AllergyVerificationStatus.REFUTED
                && a.getVerificationStatus() != AllergyVerificationStatus.ENTERED_IN_ERROR)
            .map(a -> AfterVisitSummaryDTO.Allergy.builder()
                .allergen(firstNonBlank(a.getAllergenDisplay(), a.getAllergenCode()))
                .severity(a.getSeverity() != null ? a.getSeverity().name() : null)
                .reaction(trimToNull(a.getReaction()))
                .build())
            .filter(a -> a.getAllergen() != null)
            .toList();
    }

    /**
     * The courses running today at the visit's hospital, by the rule the
     * patient's own list and the clinician's snapshot use: an order that was
     * issued (never a draft or a discarded proposal, QA D1537), and active
     * (not withdrawn, finished or ended with a discharged stay).
     */
    private List<AfterVisitSummaryDTO.CurrentMedication> currentMedications(UUID patientId, UUID hospitalId) {
        List<Prescription> issued = prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            .stream()
            .filter(PrescriptionIssuance::wasIssued)
            .toList();
        if (issued.isEmpty()) {
            return List.of();
        }
        List<Admission> closedStays = admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)
            .stream()
            .filter(a -> a.getActualDischargeDateTime() != null)
            .toList();
        return issued.stream()
            .filter(rx -> MedicationCourseStatus.isActive(rx, closedStays))
            .map(rx -> AfterVisitSummaryDTO.CurrentMedication.builder()
                .name(firstNonBlank(rx.getMedicationDisplayName(), rx.getMedicationName()))
                .dosage(trimToNull(rx.getDosage()))
                .frequency(trimToNull(rx.getFrequency()))
                .build())
            .filter(m -> m.getName() != null)
            .toList();
    }

    /** The patient's diagnoses recorded at this hospital, less one removed as entered in error (QA D1240). */
    private List<AfterVisitSummaryDTO.MedicalHistoryEntry> medicalHistory(UUID patientId, UUID hospitalId) {
        return problemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(p -> !p.isEnteredInError())
            .map(p -> AfterVisitSummaryDTO.MedicalHistoryEntry.builder()
                .condition(firstNonBlank(p.getProblemDisplay(), p.getProblemCode()))
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .build())
            .filter(e -> e.getCondition() != null)
            .toList();
    }

    private List<AfterVisitSummaryDTO.SurgicalHistoryEntry> surgicalHistory(UUID patientId, UUID hospitalId) {
        return surgicalHistoryRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .map(s -> AfterVisitSummaryDTO.SurgicalHistoryEntry.builder()
                .procedure(firstNonBlank(s.getProcedureDisplay(), s.getProcedureCode()))
                .date(s.getProcedureDate())
                .build())
            .filter(e -> e.getProcedure() != null)
            .toList();
    }

    /**
     * The family (hereditary) history recorded in THIS patient's chart. For a
     * child, that is what was recorded in the child's own chart about the
     * mother or the father; the parent's own chart is never read.
     */
    private List<AfterVisitSummaryDTO.FamilyHistoryEntry> familyHistory(UUID patientId, UUID hospitalId) {
        return familyHistoryRepository.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(patientId, hospitalId)
            .stream()
            .filter(f -> !Boolean.FALSE.equals(f.getActive()))
            .map(f -> AfterVisitSummaryDTO.FamilyHistoryEntry.builder()
                .relationship(trimToNull(f.getRelationship()))
                .condition(firstNonBlank(f.getConditionDisplay(), f.getConditionCode()))
                .build())
            .filter(e -> e.getCondition() != null)
            .toList();
    }

    /**
     * Tobacco, alcohol and occupation from the latest active record. The rest
     * of the social history (sexual health, abuse, safety at home) stays on the
     * chart: a printed summary travels.
     */
    private AfterVisitSummaryDTO.SocialHistory socialHistory(UUID patientId, UUID hospitalId) {
        return socialHistoryRepository.findByPatient_IdAndHospital_IdOrderByRecordedDateDesc(patientId, hospitalId)
            .stream()
            .filter(s -> !Boolean.FALSE.equals(s.getActive()))
            .findFirst()
            .map(s -> AfterVisitSummaryDTO.SocialHistory.builder()
                .tobaccoUse(s.getTobaccoUse())
                .alcoholUse(s.getAlcoholUse())
                .occupation(trimToNull(s.getOccupation()))
                .build())
            .filter(s -> s.getTobaccoUse() != null || s.getAlcoholUse() != null || s.getOccupation() != null)
            .orElse(null);
    }

    private static String firstNonBlank(String first, String second) {
        String a = trimToNull(first);
        return a != null ? a : trimToNull(second);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
