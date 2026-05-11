package com.example.hms.service;

import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientDiagnosisRepository;
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

        return PatientSnapshotDTO.builder()
                .patientId(patient.getId())
                .name(patient.getFirstName() + " " + patient.getLastName())
                .age(computeAge(patient))
                .sex(patient.getGender())
                .mrn(patient.getId().toString())
                .codeStatus(patient.getCodeStatus())
                .allergies(buildAllergies(patientId, patient))
                .activeDiagnoses(buildActiveDiagnoses(patientId, patient))
                .activeMedications(buildActiveMedications(patientId))
                .recentVitals(buildRecentVitals(patientId))
                .latestLabs(buildLatestLabs(patientId))
                .pendingOrders(buildPendingOrders(patientId))
                .recentNotes(buildRecentNotes(patientId))
                .careTeam(buildCareTeam(patientId))
                .build();
    }

    private int computeAge(Patient patient) {
        return patient.getDateOfBirth() != null
                ? Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears()
                : 0;
    }

    private List<String> buildAllergies(UUID patientId, Patient patient) {
        List<String> allergies = new ArrayList<>();
        try {
            patientAllergyRepository.findByPatient_Id(patientId)
                    .forEach(a -> allergies.add(a.getAllergenDisplay()));
        } catch (Exception e) {
            log.debug("Allergy query error", e);
        }
        // Include legacy free-text allergies from the patient record.
        if (patient.getAllergies() != null && !patient.getAllergies().isBlank()) {
            allergies.add(patient.getAllergies());
        }
        return allergies;
    }

    private List<String> buildActiveDiagnoses(UUID patientId, Patient patient) {
        List<String> diagnoses = new ArrayList<>();
        try {
            List<PatientDiagnosis> structured = patientDiagnosisRepository
                    .findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, DIAGNOSIS_STATUS_ACTIVE);
            structured.stream().map(this::formatDiagnosis).forEach(diagnoses::add);
        } catch (Exception e) {
            log.debug("PatientDiagnosis query error", e);
        }
        if (diagnoses.isEmpty()) {
            appendLegacyChronicConditions(diagnoses, patient);
        }
        return diagnoses;
    }

    private String formatDiagnosis(PatientDiagnosis d) {
        return d.getIcdCode() != null
                ? d.getIcdCode() + " – " + d.getDescription()
                : d.getDescription();
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

    private List<PatientSnapshotDTO.MedicationItem> buildActiveMedications(UUID patientId) {
        List<PatientSnapshotDTO.MedicationItem> medications = new ArrayList<>();
        try {
            prescriptionRepository.findByPatient_Id(patientId, PageRequest.of(0, 10))
                    .forEach(rx -> medications.add(PatientSnapshotDTO.MedicationItem.builder()
                            .name(rx.getMedicationName())
                            .dose(rx.getDosage())
                            .frequency(rx.getFrequency())
                            .build()));
        } catch (Exception e) {
            log.debug("Prescription query error: {}", e.getMessage());
        }
        return medications;
    }

    private List<PatientSnapshotDTO.VitalItem> buildRecentVitals(UUID patientId) {
        List<PatientSnapshotDTO.VitalItem> vitals = new ArrayList<>();
        try {
            patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, 5))
                    .forEach(v -> vitals.add(PatientSnapshotDTO.VitalItem.builder()
                            .type("Vitals")
                            .value(summarizeVitals(v))
                            .timestamp(v.getRecordedAt() != null ? v.getRecordedAt().format(DATE_FMT) : "")
                            .build()));
        } catch (Exception e) {
            log.debug("Vitals query error: {}", e.getMessage());
        }
        return vitals;
    }

    private String summarizeVitals(com.example.hms.model.PatientVitalSign v) {
        StringBuilder s = new StringBuilder();
        if (v.getTemperatureCelsius() != null) s.append("T:").append(v.getTemperatureCelsius()).append("°C ");
        if (v.getHeartRateBpm() != null) s.append("HR:").append(v.getHeartRateBpm()).append(" ");
        if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null) {
            s.append("BP:").append(v.getSystolicBpMmHg()).append("/").append(v.getDiastolicBpMmHg()).append(" ");
        }
        if (v.getSpo2Percent() != null) s.append("SpO2:").append(v.getSpo2Percent()).append("% ");
        return s.toString().trim();
    }

    private List<PatientSnapshotDTO.LabItem> buildLatestLabs(UUID patientId) {
        List<PatientSnapshotDTO.LabItem> labs = new ArrayList<>();
        try {
            labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
                    .limit(10)
                    .forEach(r -> labs.add(PatientSnapshotDTO.LabItem.builder()
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

    private List<PatientSnapshotDTO.OrderItem> buildPendingOrders(UUID patientId) {
        List<PatientSnapshotDTO.OrderItem> pendingOrders = new ArrayList<>();
        try {
            labOrderRepository.findByPatient_Id(patientId).stream()
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

    private List<PatientSnapshotDTO.CareTeamMember> buildCareTeam(UUID patientId) {
        List<PatientSnapshotDTO.CareTeamMember> careTeam = new ArrayList<>();
        try {
            encounterRepository.findByPatient_Id(patientId).stream()
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

    private List<PatientSnapshotDTO.NoteItem> buildRecentNotes(UUID patientId) {
        List<PatientSnapshotDTO.NoteItem> recentNotes = new ArrayList<>();
        try {
            encounterRepository.findByPatient_Id(patientId).stream()
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
