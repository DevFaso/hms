package com.example.hms.service;

import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.repository.PatientDiagnosisRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.CrossHospitalRows;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.model.Encounter;
import com.example.hms.model.PatientAllergy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds the patient-snapshot DTO for the chart-summary view.
 *
 * <p>Sonar S3776 — the previous monolithic {@code getSnapshot} sat at cognitive
 * complexity 65 (and tripped Brain Method). The eight section builders below
 * each own one DTO field and are short, individually testable, and swallow
 * their own DB errors so a flaky non-essential section can't blank the whole
 * snapshot. {@code getSnapshot} is now a thin assembler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientSnapshotServiceImpl implements PatientSnapshotService {

    private final PatientRepository patientRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final PatientVitalSignRepository patientVitalSignRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final EncounterRepository encounterRepository;
    private final PatientDiagnosisRepository patientDiagnosisRepository;
    private final PatientProblemRepository patientProblemRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final SensitivityClassifier sensitivityClassifier;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DIAGNOSIS_STATUS_ACTIVE = "ACTIVE";
    private static final String FLAG_NORMAL = "NORMAL";
    private static final String FLAG_REVIEW = "REVIEW";

    @Override
    public PatientSnapshotDTO getSnapshot(UUID patientId, UUID hospitalId) {
        log.info("Building patient snapshot for: {}", patientId);

        Patient patient = patientRepository.findByIdUnscoped(patientId)
                .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException("Patient not found: " + patientId));

        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new com.example.hms.exception.BusinessException("Patient is not registered at this hospital.");
        }

        // E9 #60 — the snapshot follows the patient: with an acting hospital every
        // section reads the policy's readable set (allergies stay patient-wide,
        // #56) and every foreign row surfaced is accounted once for the whole
        // snapshot. Without one (super-admin global view) the patient-wide reads
        // stay. A foreign encounter in a sensitive category (D3) is withheld.
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = hospitalId == null ? null
                : recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        Map<String, Long> reach = new HashMap<>();
        List<Encounter> encounters = loadEncounters(patientId, hospitalId, readable, reach);
        PatientSnapshotDTO snapshot = PatientSnapshotDTO.builder()
                .patientId(patient.getId())
                .name(patient.getFirstName() + " " + patient.getLastName())
                .age(computeAge(patient))
                .sex(patient.getGender())
                .mrn(patient.getId().toString())
                .codeStatus(patient.getCodeStatus())
                .allergies(buildAllergies(patientId, patient, hospitalId, reach))
                .activeDiagnoses(buildActiveDiagnoses(patientId, patient, hospitalId, readable, reach))
                .activeMedications(buildActiveMedications(patientId, hospitalId, readable, reach))
                .recentVitals(buildRecentVitals(patientId, hospitalId, readable, reach))
                .latestLabs(buildLatestLabs(patientId, hospitalId, readable, reach))
                .pendingOrders(buildPendingOrders(patientId, hospitalId, readable, reach))
                .recentNotes(buildRecentNotes(encounters))
                .careTeam(buildCareTeam(encounters))
                .build();
        if (hospitalId != null) {
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null, reach,
                    "Cross-hospital patient snapshot read on the treatment relationship");
        }
        return snapshot;
    }

    /** E9 #60 — one row per foreign hospital surfaced, merged into the snapshot's reach. */
    private static void account(Map<String, Long> reach, UUID actingHospitalId, List<UUID> sourceHospitalIds) {
        if (actingHospitalId != null) {
            CrossHospitalReachRecorder.merge(reach, CrossHospitalReachRecorder.reachOf(sourceHospitalIds, actingHospitalId));
        }
    }

    private List<Encounter> loadEncounters(UUID patientId, UUID hospitalId, Set<UUID> readable, Map<String, Long> reach) {
        try {
            List<Encounter> rows = readable == null
                    ? encounterRepository.findByPatient_Id(patientId)
                    : encounterRepository.findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(patientId, readable).stream()
                        .filter(e -> CrossHospitalRows.maySurface(e.getHospital(), hospitalId, sensitivityClassifier.effectiveCategory(e)))
                        .toList();
            account(reach, hospitalId, rows.stream().map(e -> CrossHospitalReachRecorder.hospitalIdOf(e.getHospital())).toList());
            return rows;
        } catch (Exception e) {
            log.debug("Encounter query error: {}", e.getMessage());
            return List.of();
        }
    }

    private int computeAge(Patient patient) {
        return patient.getDateOfBirth() != null
                ? Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears()
                : 0;
    }

    private List<String> buildAllergies(UUID patientId, Patient patient, UUID hospitalId, Map<String, Long> reach) {
        List<String> allergies = new ArrayList<>();
        try {
            List<PatientAllergy> rows = patientAllergyRepository.findByPatient_Id(patientId);
            rows.forEach(a -> allergies.add(a.getAllergenDisplay()));
            account(reach, hospitalId, rows.stream().map(a -> CrossHospitalReachRecorder.hospitalIdOf(a.getHospital())).toList());
        } catch (Exception e) {
            log.debug("Allergy query error", e);
        }
        // E9 #56 — the free-text column is a derived summary of the rows above;
        // it is read only when there is no structured row at all (a patient the
        // startup backfill could not place at a hospital), never in addition.
        if (allergies.isEmpty() && patient.getAllergies() != null && !patient.getAllergies().isBlank()) {
            allergies.add(patient.getAllergies());
        }
        return allergies;
    }

    /**
     * Active diagnoses for the snapshot, from BOTH stores.
     *
     * <p>{@code clinical.patient_problems} is where every current write path
     * lands, so reading only {@code patient_diagnoses} meant the snapshot
     * showed nothing recorded since that table was superseded, then silently
     * fell through to the legacy free-text chronic conditions as if the
     * patient had no structured diagnoses at all. {@code patient_diagnoses}
     * is still read for V14-era rows and is read-only legacy.
     */
    private List<String> buildActiveDiagnoses(UUID patientId, Patient patient, UUID hospitalId,
                                              Set<UUID> readable, Map<String, Long> reach) {
        List<String> diagnoses = new ArrayList<>();
        try {
            List<com.example.hms.model.PatientProblem> problems = readable == null
                    ? patientProblemRepository.findByPatient_IdAndStatusOrderByCreatedAtDesc(patientId, ProblemStatus.ACTIVE)
                    : patientProblemRepository.findByPatient_IdAndHospital_IdIn(patientId, readable).stream()
                        .filter(p -> p.getStatus() == ProblemStatus.ACTIVE)
                        .filter(p -> CrossHospitalRows.maySurface(p.getHospital(), hospitalId, sensitivityClassifier.effectiveCategory(p)))
                        .toList();
            account(reach, hospitalId, problems.stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList());
            problems.stream()
                    .map(p -> formatDiagnosis(p.getProblemCode(), p.getProblemDisplay()))
                    .forEach(diagnoses::add);
            List<PatientDiagnosis> legacy = patientDiagnosisRepository
                    .findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, DIAGNOSIS_STATUS_ACTIVE);
            legacy.stream()
                    .map(d -> formatDiagnosis(d.getIcdCode(), d.getDescription()))
                    .forEach(diagnoses::add);
        } catch (RuntimeException e) {
            // WARN, not debug: this used to hide a query failure behind the
            // same empty result an absent diagnosis produces, so a broken
            // read looked identical to a patient with no problems.
            log.warn("Active-diagnosis lookup failed for patient {}", patientId, e);
        }
        if (diagnoses.isEmpty()) {
            appendLegacyChronicConditions(diagnoses, patient);
        }
        return diagnoses;
    }

    /** "CODE – description", or the description alone when no code was coded. */
    private String formatDiagnosis(String code, String description) {
        return code != null ? code + " – " + description : description;
    }

    private void appendLegacyChronicConditions(List<String> diagnoses, Patient patient) {
        String conditions = patient.getChronicConditions();
        if (conditions == null || conditions.isBlank()) {
            return;
        }
        for (String cond : conditions.split("[,;]")) {
            String trimmed = cond.trim();
            if (!trimmed.isEmpty()) {
                diagnoses.add(trimmed);
            }
        }
    }

    private List<PatientSnapshotDTO.MedicationItem> buildActiveMedications(UUID patientId, UUID hospitalId,
                                                                          Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.MedicationItem> medications = new ArrayList<>();
        try {
            List<com.example.hms.model.Prescription> rows = (readable == null
                    ? prescriptionRepository.findByPatient_Id(patientId, PageRequest.of(0, 10))
                    : prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, readable, PageRequest.of(0, 10)))
                    .getContent();
            account(reach, hospitalId, rows.stream().map(rx -> CrossHospitalReachRecorder.hospitalIdOf(rx.getHospital())).toList());
            rows.forEach(rx -> medications.add(PatientSnapshotDTO.MedicationItem.builder()
                            .name(rx.getMedicationName())
                            .dose(rx.getDosage())
                            .frequency(rx.getFrequency())
                            .build()));
        } catch (Exception e) {
            log.debug("Prescription query error: {}", e.getMessage());
        }
        return medications;
    }

    private List<PatientSnapshotDTO.VitalItem> buildRecentVitals(UUID patientId, UUID hospitalId,
                                                                Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.VitalItem> vitals = new ArrayList<>();
        try {
            List<PatientVitalSign> rows = readable == null
                    ? patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, 5))
                    : patientVitalSignRepository.findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(patientId, readable, PageRequest.of(0, 5));
            account(reach, hospitalId, rows.stream().map(v -> CrossHospitalReachRecorder.hospitalIdOf(v.getHospital())).toList());
            rows.forEach(v -> vitals.add(PatientSnapshotDTO.VitalItem.builder()
                            .type("Vitals")
                            .value(summarizeVitals(v))
                            .timestamp(v.getRecordedAt() != null ? v.getRecordedAt().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Vitals query error: {}", e.getMessage());
        }
        return vitals;
    }

    private String summarizeVitals(PatientVitalSign v) {
        StringBuilder s = new StringBuilder();
        if (v.getTemperatureCelsius() != null) s.append("T:").append(v.getTemperatureCelsius()).append("°C ");
        if (v.getHeartRateBpm() != null) s.append("HR:").append(v.getHeartRateBpm()).append(" ");
        if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null) {
            s.append("BP:").append(v.getSystolicBpMmHg()).append("/").append(v.getDiastolicBpMmHg()).append(" ");
        }
        if (v.getSpo2Percent() != null) s.append("SpO2:").append(v.getSpo2Percent()).append("% ");
        return s.toString().trim();
    }

    private List<PatientSnapshotDTO.LabItem> buildLatestLabs(UUID patientId, UUID hospitalId,
                                                            Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.LabItem> labs = new ArrayList<>();
        try {
            // Paged at the DB so the patient's full lab history is never loaded
            // to trim to 10 after the fact.
            List<LabResult> rows = readable == null
                    ? labResultRepository.findByLabOrder_Patient_Id(patientId, PageRequest.of(0, 10)).getContent()
                    : labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(patientId, readable, PageRequest.of(0, 10));
            account(reach, hospitalId, rows.stream()
                    .map(r -> r.getLabOrder() == null ? null : CrossHospitalReachRecorder.hospitalIdOf(r.getLabOrder().getHospital()))
                    .toList());
            rows.forEach(r -> labs.add(PatientSnapshotDTO.LabItem.builder()
                            .test(r.getLabOrder().getLabTestDefinition() != null
                                    ? r.getLabOrder().getLabTestDefinition().getName()
                                    : "Lab Test")
                            .value(r.getResultValue())
                            .flag(labResultFlag(r))
                            .date(r.getResultDate() != null ? r.getResultDate().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Lab results query error: {}", e.getMessage());
        }
        return labs;
    }

    private String labResultFlag(LabResult r) {
        if (r.getAbnormalFlag() != null) {
            return r.getAbnormalFlag().name();
        }
        return r.isAcknowledged() ? FLAG_NORMAL : FLAG_REVIEW;
    }

    private List<PatientSnapshotDTO.OrderItem> buildPendingOrders(UUID patientId, UUID hospitalId,
                                                                 Set<UUID> readable, Map<String, Long> reach) {
        List<PatientSnapshotDTO.OrderItem> pendingOrders = new ArrayList<>();
        try {
            List<com.example.hms.model.LabOrder> rows = readable == null
                    ? labOrderRepository.findByPatient_Id(patientId)
                    : labOrderRepository.findByPatient_IdAndHospital_IdIn(patientId, readable);
            account(reach, hospitalId, rows.stream().map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital())).toList());
            rows.stream()
                    .filter(o -> o.getStatus() == com.example.hms.enums.LabOrderStatus.PENDING
                            || o.getStatus() == com.example.hms.enums.LabOrderStatus.IN_PROGRESS)
                    .limit(10)
                    .forEach(o -> pendingOrders.add(PatientSnapshotDTO.OrderItem.builder()
                            .type("Lab")
                            .description(o.getLabTestDefinition() != null ? o.getLabTestDefinition().getName() : "Lab Order")
                            .orderedAt(o.getOrderDatetime() != null ? o.getOrderDatetime().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Pending orders query error: {}", e.getMessage());
        }
        return pendingOrders;
    }

    private List<PatientSnapshotDTO.CareTeamMember> buildCareTeam(List<Encounter> encounters) {
        List<PatientSnapshotDTO.CareTeamMember> careTeam = new ArrayList<>();
        try {
            encounters.stream()
                    .filter(e -> e.getStaff() != null)
                    .map(e -> PatientSnapshotDTO.CareTeamMember.builder()
                            .role(e.getStaff().getJobTitle() != null ? e.getStaff().getJobTitle().name() : "Staff")
                            .name(e.getStaff().getFullName())
                            .build())
                    .distinct()
                    .limit(10)
                    .forEach(careTeam::add);
        } catch (Exception e) {
            log.debug("Care team query error: {}", e.getMessage());
        }
        return careTeam;
    }

    private List<PatientSnapshotDTO.NoteItem> buildRecentNotes(List<Encounter> encounters) {
        List<PatientSnapshotDTO.NoteItem> recentNotes = new ArrayList<>();
        try {
            encounters.stream()
                    .filter(e -> e.getNotes() != null && !e.getNotes().isBlank())
                    .sorted(java.util.Comparator.comparing(
                            e -> e.getEncounterDate() != null ? e.getEncounterDate() : java.time.LocalDateTime.MIN,
                            java.util.Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(e -> recentNotes.add(PatientSnapshotDTO.NoteItem.builder()
                            .author(e.getStaff() != null ? e.getStaff().getFullName() : "Unknown")
                            .type(e.getEncounterType() != null ? e.getEncounterType().name() : "Encounter")
                            .date(e.getEncounterDate() != null ? e.getEncounterDate().format(DATE_FMT) : "")
                            .snippet(truncateSnippet(e.getNotes()))
                            .build()));
        } catch (Exception e) {
            log.debug("Recent notes query error: {}", e.getMessage());
        }
        return recentNotes;
    }

    private String truncateSnippet(String notes) {
        return notes.length() > 200 ? notes.substring(0, 200) + "…" : notes;
    }
}
