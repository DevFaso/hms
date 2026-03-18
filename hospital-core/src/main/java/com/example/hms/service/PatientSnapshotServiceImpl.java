package com.example.hms.service;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientDiagnosis;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

    @Override
    public PatientSnapshotDTO getSnapshot(UUID patientId) {
        log.info("Building patient snapshot for: {}", patientId);

        Patient patient = patientRepository.findByIdUnscoped(patientId)
                .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException("Patient not found: " + patientId));

        int age = patient.getDateOfBirth() != null
                ? Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears()
                : 0;

        // Allergies
        List<String> allergies = new ArrayList<>();
        try {
            patientAllergyRepository.findByPatient_Id(patientId).forEach(a ->
                    allergies.add(a.getAllergenDisplay()));
        } catch (Exception e) {
            log.debug("Allergy query error: {}", e.getMessage());
        }
        // Also include legacy free-text allergies from patient record
        if (patient.getAllergies() != null && !patient.getAllergies().isBlank()) {
            allergies.add(patient.getAllergies());
        }

        // Active diagnoses — prefer structured diagnosis records; fall back to chronicConditions string
        List<String> diagnoses = new ArrayList<>();
        try {
            List<PatientDiagnosis> structured =
                    patientDiagnosisRepository.findByPatient_IdAndStatusOrderByDiagnosedAtDesc(patientId, "ACTIVE");
            if (!structured.isEmpty()) {
                for (PatientDiagnosis d : structured) {
                    String label = d.getIcdCode() != null
                            ? d.getIcdCode() + " – " + d.getDescription()
                            : d.getDescription();
                    diagnoses.add(label);
                }
            }
        } catch (Exception e) {
            log.debug("PatientDiagnosis query error: {}", e.getMessage());
        }
        // Fallback: parse legacy chronicConditions free-text field when no structured records exist
        if (diagnoses.isEmpty() && patient.getChronicConditions() != null && !patient.getChronicConditions().isBlank()) {
            for (String cond : patient.getChronicConditions().split("[,;]")) {
                String trimmed = cond.trim();
                if (!trimmed.isEmpty()) diagnoses.add(trimmed);
            }
        }

        // Active medications from prescriptions (latest 10)
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

        // Recent vitals (last 5)
        List<PatientSnapshotDTO.VitalItem> vitals = new ArrayList<>();
        try {
            patientVitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, 5))
                    .forEach(v -> {
                        StringBuilder summary = new StringBuilder();
                        if (v.getTemperatureCelsius() != null) summary.append("T:").append(v.getTemperatureCelsius()).append("°C ");
                        if (v.getHeartRateBpm() != null) summary.append("HR:").append(v.getHeartRateBpm()).append(" ");
                        if (v.getSystolicBpMmHg() != null && v.getDiastolicBpMmHg() != null)
                            summary.append("BP:").append(v.getSystolicBpMmHg()).append("/").append(v.getDiastolicBpMmHg()).append(" ");
                        if (v.getSpo2Percent() != null) summary.append("SpO2:").append(v.getSpo2Percent()).append("% ");

                        vitals.add(PatientSnapshotDTO.VitalItem.builder()
                                .type("Vitals")
                                .value(summary.toString().trim())
                                .timestamp(v.getRecordedAt() != null ? v.getRecordedAt().format(DATE_FMT) : "")
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Vitals query error: {}", e.getMessage());
        }

        // Latest labs (from lab results)
        List<PatientSnapshotDTO.LabItem> labs = new ArrayList<>();
        try {
            labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
                    .limit(10)
                    .forEach(r -> {
                        String flag;
                        if (r.getAbnormalFlag() != null) {
                            flag = r.getAbnormalFlag().name();
                        } else {
                            flag = r.isAcknowledged() ? "NORMAL" : "REVIEW";
                        }
                        labs.add(PatientSnapshotDTO.LabItem.builder()
                            .test(r.getLabOrder().getLabTestDefinition() != null ? r.getLabOrder().getLabTestDefinition().getName() : "Lab Test")
                            .value(r.getResultValue())
                            .flag(flag)
                            .date(r.getResultDate() != null ? r.getResultDate().format(DATE_FMT) : "")
                            .build());
                    });
        } catch (Exception e) {
            log.debug("Lab results query error: {}", e.getMessage());
        }

        // Pending orders
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

        // Care team from encounters
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

        List<PatientSnapshotDTO.NoteItem> recentNotes = new ArrayList<>();
        try {
            encounterRepository.findByPatient_Id(patientId).stream()
                    .filter(e -> e.getNotes() != null && !e.getNotes().isBlank())
                    .sorted(java.util.Comparator.comparing(
                            e -> e.getEncounterDate() != null ? e.getEncounterDate() : java.time.LocalDateTime.MIN,
                            java.util.Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(e -> {
                        String snippet = e.getNotes().length() > 200
                                ? e.getNotes().substring(0, 200) + "…"
                                : e.getNotes();
                        recentNotes.add(PatientSnapshotDTO.NoteItem.builder()
                                .author(e.getStaff() != null ? e.getStaff().getFullName() : "Unknown")
                                .type(e.getEncounterType() != null ? e.getEncounterType().name() : "Encounter")
                                .date(e.getEncounterDate() != null ? e.getEncounterDate().format(DATE_FMT) : "")
                                .snippet(snippet)
                                .build());
                    });
        } catch (Exception e) {
            log.debug("Recent notes query error: {}", e.getMessage());
        }

        return PatientSnapshotDTO.builder()
                .patientId(patient.getId())
                .name(patient.getFirstName() + " " + patient.getLastName())
                .age(age)
                .sex(patient.getGender())
                .mrn(patient.getId().toString())
                .codeStatus(patient.getCodeStatus())
                .allergies(allergies)
                .activeDiagnoses(diagnoses)
                .activeMedications(medications)
                .recentVitals(vitals)
                .latestLabs(labs)
                .pendingOrders(pendingOrders)
                .recentNotes(recentNotes)
                .careTeam(careTeam)
                .build();
    }
}
