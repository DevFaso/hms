package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Announcement;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationAdministrationRecord;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.NurseTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NurseTaskServiceImpl implements NurseTaskService {
    private static final String SAMPLE_PATIENT_NAME = "Sample Patient";

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(2);
    private static final int DEFAULT_LIMIT = 6;
    private static final int MAX_LIMIT = 20;
    private static final long VITALS_OVERDUE_HOURS = 4;

    private static final String STATUS_OVERDUE = "OVERDUE";
    private static final String STATUS_DUE = "DUE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String SEED_PATIENT = "PATIENT";
    private static final String SEED_VITAL = "VITAL";
    private static final String SEED_MEDICATION = "MEDICATION";
    private static final String SEED_ORDER = "ORDER";
    private static final String SEED_HANDOFF = "HANDOFF";
    private static final String DEFAULT_HOSPITAL_SEED = "HOSPITAL";
    private static final String DEFAULT_PATIENT_NAME = "Patient";
    private static final String DEFAULT_ADMINISTRATION_STATUS = "GIVEN";
    private static final Set<String> SUPPORTED_ADMINISTRATION_STATUSES = Set.of(
        DEFAULT_ADMINISTRATION_STATUS,
        "HELD",
        "REFUSED",
        "MISSED"
    );

    private final NurseDashboardService nurseDashboardService;
    private final PrescriptionRepository prescriptionRepository;
    private final PatientVitalSignRepository patientVitalSignRepository;
    private final MedicationAdministrationRecordRepository marRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditEventLogService auditEventLogService;

    private record PatientContext(UUID patientId, String displayName) {
    }

    // ── Vitals: query real PatientVitalSign to find patients with overdue vitals ──

    @Override
    public List<NurseVitalTaskResponseDTO> getDueVitals(UUID nurseUserId, UUID hospitalId, Duration window) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime overdueCutoff = now.minusHours(VITALS_OVERDUE_HOURS);

        List<NurseVitalTaskResponseDTO> result = new ArrayList<>();
        for (PatientContext patient : patients) {
            if (patient.patientId() == null) continue;

            java.util.Optional<PatientVitalSign> latest = hospitalId != null
                ? patientVitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patient.patientId(), hospitalId)
                : patientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patient.patientId());

            boolean overdue;
            LocalDateTime dueTime;

            if (latest.isEmpty()) {
                overdue = true;
                dueTime = now.minusHours(1);
            } else {
                LocalDateTime lastRecorded = latest.get().getRecordedAt();
                dueTime = lastRecorded.plusHours(VITALS_OVERDUE_HOURS);
                overdue = dueTime.isBefore(now);
            }

            result.add(NurseVitalTaskResponseDTO.builder()
                .id(generateStableId(patient.displayName(), hospitalId, SEED_VITAL, result.size()))
                .patientId(patient.patientId())
                .patientName(patient.displayName())
                .type("Full Vitals")
                .dueTime(dueTime)
                .overdue(overdue)
                .build());
        }

        if (result.isEmpty()) {
            // Fall back to synthetic data
            Duration effectiveWindow = normalizeWindow(window);
            return IntStream.range(0, Math.min(DEFAULT_LIMIT, patients.size()))
                .mapToObj(index -> createSyntheticVitalTask(patients.get(index), hospitalId, now, effectiveWindow, index))
                .sorted(Comparator.comparing(NurseVitalTaskResponseDTO::getDueTime))
                .toList();
        }

        result.sort(Comparator.comparing(NurseVitalTaskResponseDTO::getDueTime));
        return result;
    }

    // ── Medications: query real Prescriptions and generate MAR rows ──

    @Override
    public List<NurseMedicationTaskResponseDTO> getMedicationTasks(UUID nurseUserId, UUID hospitalId, String statusFilter) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        LocalDateTime now = LocalDateTime.now();

        List<NurseMedicationTaskResponseDTO> realTasks = new ArrayList<>();
        if (hospitalId != null) {
            for (PatientContext patient : patients) {
                if (patient.patientId() == null) continue;
                List<Prescription> prescriptions = prescriptionRepository
                    .findByPatient_IdAndHospital_Id(patient.patientId(), hospitalId);

                for (Prescription rx : prescriptions) {
                    if (rx.getStatus() == PrescriptionStatus.CANCELLED
                        || rx.getStatus() == PrescriptionStatus.DISCONTINUED) {
                        continue;
                    }
                    String medName = rx.getMedicationDisplayName() != null
                        ? rx.getMedicationDisplayName() : rx.getMedicationName();
                    String dose = rx.getDosage() != null ? rx.getDosage() : "";
                    if (!dose.isEmpty() && rx.getDoseUnit() != null) {
                        dose = dose + " " + rx.getDoseUnit();
                    }
                    String route = rx.getRoute() != null ? rx.getRoute() : "PO";

                    // Generate a scheduled time based on frequency (simplified: next due now)
                    LocalDateTime scheduledTime = now.plusMinutes(30L * (realTasks.size() + 1));
                    String status = determineMedicationStatus(statusFilter, scheduledTime, now, realTasks.size());

                    realTasks.add(NurseMedicationTaskResponseDTO.builder()
                        .id(rx.getId())
                        .patientId(patient.patientId())
                        .patientName(patient.displayName())
                        .medication(medName)
                        .dose(dose)
                        .route(route)
                        .dueTime(scheduledTime)
                        .status(status)
                        .build());
                }
            }
        }

        if (!realTasks.isEmpty()) {
            return realTasks;
        }

        // Fallback to synthetic data
        return IntStream.range(0, Math.min(MAX_LIMIT, patients.size()))
            .mapToObj(index -> createSyntheticMedicationTask(patients.get(index), hospitalId, now, statusFilter, index))
            .toList();
    }

    @Override
    public List<NurseOrderTaskResponseDTO> getOrderTasks(UUID nurseUserId, UUID hospitalId, String statusFilter, int limit) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        int effectiveLimit = clampLimit(limit);
        LocalDateTime now = LocalDateTime.now();
        String normalized = statusFilter != null && !statusFilter.isBlank()
            ? statusFilter.trim().toUpperCase(Locale.ROOT)
            : null;

        return IntStream.range(0, Math.min(effectiveLimit, patients.size()))
            .mapToObj(index -> createOrderTask(patients.get(index), hospitalId, now, index))
            .filter(task -> normalized == null
                || (task.getPriority() != null && normalized.equals(task.getPriority().toUpperCase(Locale.ROOT))))
            .toList();
    }

    @Override
    public List<NurseHandoffSummaryDTO> getHandoffSummaries(UUID nurseUserId, UUID hospitalId, int limit) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        int effectiveLimit = clampLimit(limit);
        LocalDate today = LocalDate.now();

        return IntStream.range(0, Math.min(effectiveLimit, patients.size()))
            .mapToObj(index -> createHandoffSummary(patients.get(index), hospitalId, today, index))
            .toList();
    }

    @Override
    @Transactional
    public void completeHandoff(UUID handoffId, UUID nurseUserId, UUID hospitalId) {
        if (handoffId == null) {
            throw new BusinessException("Handoff identifier is required.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context required to complete handoff.");
        }

        getHandoffSummaries(nurseUserId, hospitalId, DEFAULT_LIMIT);
    }

    // ── Announcements: query real Announcement entity ──

    @Override
    public List<NurseAnnouncementDTO> getAnnouncements(UUID hospitalId, int limit) {
        int effectiveLimit = clampLimit(limit);

        List<Announcement> announcements = announcementRepository.findAll(
            PageRequest.of(0, effectiveLimit, Sort.by(Sort.Direction.DESC, "date"))
        ).getContent();

        if (!announcements.isEmpty()) {
            return announcements.stream()
                .map(a -> NurseAnnouncementDTO.builder()
                    .id(a.getId())
                    .text(a.getText())
                    .createdAt(a.getDate())
                    .startsAt(a.getDate())
                    .expiresAt(a.getDate().plusHours(12))
                    .category("OPERATIONS")
                    .build())
                .toList();
        }

        // Fallback to synthetic announcements
        LocalDateTime now = LocalDateTime.now();
        String hospitalLabel = hospitalId != null ? abbreviateHospitalId(hospitalId) : DEFAULT_HOSPITAL_SEED;
        return IntStream.range(0, effectiveLimit)
            .mapToObj(index -> createSyntheticAnnouncement(now, hospitalLabel, index))
            .toList();
    }

    // ── Medication Administration: persist to MedicationAdministrationRecord ──

    @Override
    @Transactional
    public NurseMedicationTaskResponseDTO recordMedicationAdministration(
        UUID medicationTaskId,
        UUID nurseUserId,
        UUID hospitalId,
        NurseMedicationAdministrationRequestDTO request
    ) {
        if (medicationTaskId == null) {
            throw new BusinessException("Medication task identifier is required.");
        }
        String normalizedStatus = normalizeAdministrationStatus(request);

        // Try to find a real prescription by the task ID
        java.util.Optional<Prescription> prescriptionOpt = prescriptionRepository.findById(medicationTaskId);
        if (prescriptionOpt.isPresent()) {
            Prescription rx = prescriptionOpt.get();

            // Verify prescription belongs to the requested hospital scope
            if (hospitalId != null && rx.getHospital() != null
                && !rx.getHospital().getId().equals(hospitalId)) {
                throw new ResourceNotFoundException("Medication administration task not found.");
            }

            Patient patient = rx.getPatient();
            if (patient == null) {
                throw new BusinessException("Prescription has no associated patient.");
            }
            if (rx.getHospital() == null) {
                throw new BusinessException("Prescription has no associated hospital.");
            }

            MedicationAdministrationStatus marStatus = mapToMarStatus(normalizedStatus);
            String note = request != null ? request.getNote() : null;

            MedicationAdministrationRecord mar = MedicationAdministrationRecord.builder()
                .prescription(rx)
                .patient(patient)
                .hospital(rx.getHospital())
                .medicationName(rx.getMedicationName())
                .dose(rx.getDosage())
                .route(rx.getRoute())
                .scheduledTime(LocalDateTime.now())
                .administeredAt(marStatus == MedicationAdministrationStatus.ADMINISTERED ? LocalDateTime.now() : null)
                .status(marStatus)
                .holdReason(marStatus == MedicationAdministrationStatus.HELD ? note : null)
                .refusalReason(marStatus == MedicationAdministrationStatus.REFUSED ? note : null)
                .note(note)
                .build();

            marRepository.save(mar);

            String displayName = patient.getFullName() != null ? patient.getFullName() : "Patient";

            return NurseMedicationTaskResponseDTO.builder()
                .id(rx.getId())
                .patientId(patient.getId())
                .patientName(displayName)
                .medication(rx.getMedicationDisplayName() != null ? rx.getMedicationDisplayName() : rx.getMedicationName())
                .dose(rx.getDosage())
                .route(rx.getRoute())
                .dueTime(LocalDateTime.now())
                .status(normalizedStatus)
                .build();
        }

        // Fall back to synthetic task lookup
        List<NurseMedicationTaskResponseDTO> tasks = getMedicationTasks(nurseUserId, hospitalId, null);

        return tasks.stream()
            .filter(task -> medicationTaskId.equals(task.getId()))
            .findFirst()
            .map(task -> NurseMedicationTaskResponseDTO.builder()
                .id(task.getId())
                .patientId(task.getPatientId())
                .patientName(task.getPatientName())
                .medication(task.getMedication())
                .dose(task.getDose())
                .route(task.getRoute())
                .dueTime(task.getDueTime())
                .status(normalizedStatus)
                .build())
            .orElseThrow(() -> new ResourceNotFoundException("Medication administration task not found."));
    }

    @Override
    @Transactional
    public NurseHandoffChecklistUpdateResponseDTO updateHandoffChecklistItem(
        UUID handoffId,
        UUID taskId,
        UUID nurseUserId,
        UUID hospitalId,
        boolean completed
    ) {
        if (handoffId == null) {
            throw new BusinessException("Handoff identifier is required.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context required to update handoff checklist.");
        }

        try {
            List<NurseHandoffSummaryDTO> handoffs = getHandoffSummaries(nurseUserId, hospitalId, DEFAULT_LIMIT);
            boolean handoffExists = handoffs.stream().anyMatch(handoff -> handoffId.equals(handoff.getId()));
            if (!handoffExists) {
                throw new ResourceNotFoundException("Handoff not found for checklist update.");
            }
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("Handoff not found for checklist update.");
        }

        return NurseHandoffChecklistUpdateResponseDTO.builder()
            .handoffId(handoffId)
            .taskId(taskId)
            .completed(completed)
            .completedAt(completed ? LocalDateTime.now() : null)
            .build();
    }

    // ── Synthetic fallback helpers ──

    private NurseVitalTaskResponseDTO createSyntheticVitalTask(PatientContext patient, UUID hospitalId, LocalDateTime now, Duration window, int index) {
        long dueOffset = window.toMinutes() / 2 + 12L * index;
        LocalDateTime dueTime = now.plusMinutes(dueOffset);
        boolean overdue = dueTime.isBefore(now);
        return NurseVitalTaskResponseDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_VITAL, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .type(selectVitalType(index))
            .dueTime(dueTime)
            .overdue(overdue)
            .build();
    }

    private NurseMedicationTaskResponseDTO createSyntheticMedicationTask(PatientContext patient, UUID hospitalId, LocalDateTime now, String statusFilter, int index) {
        LocalDateTime dueTime = now.plusMinutes(30L * (index + 1));
        String status = determineMedicationStatus(statusFilter, dueTime, now, index);
        return NurseMedicationTaskResponseDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_MEDICATION, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .medication(selectMedication(index))
            .dose(selectDosage(index))
            .route(index % 2 == 0 ? "IV" : "PO")
            .dueTime(dueTime)
            .status(status)
            .build();
    }

    private NurseOrderTaskResponseDTO createOrderTask(PatientContext patient, UUID hospitalId, LocalDateTime now, int index) {
        LocalDateTime dueTime = now.plusMinutes(45L * (index + 1));
        return NurseOrderTaskResponseDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_ORDER, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .orderType(selectOrderCategory(index))
            .priority(index % 3 == 0 ? "STAT" : "Routine")
            .dueTime(dueTime)
            .build();
    }

    private NurseHandoffSummaryDTO createHandoffSummary(PatientContext patient, UUID hospitalId, LocalDate today, int index) {
        LocalDateTime updatedAt = today.atStartOfDay().plusHours(7L + index);
        return NurseHandoffSummaryDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_HANDOFF, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .direction(index % 2 == 0 ? "Transfer to Radiology" : "Return from OR")
            .updatedAt(updatedAt)
            .note(index % 3 == 0 ? "High fall risk." : "Ready for handoff discussion.")
            .build();
    }

    private NurseAnnouncementDTO createSyntheticAnnouncement(LocalDateTime now, String hospitalLabel, int index) {
        long hoursOffset = 6L + index;
        return NurseAnnouncementDTO.builder()
            .id(UUID.randomUUID())
            .text(index == 0
                ? String.format("[%s] Safety huddle at 15:00.", hospitalLabel)
                : String.format("[%s] Operational update %d", hospitalLabel, index + 1))
            .createdAt(now.minusHours(index))
            .startsAt(now.minusHours(index))
            .expiresAt(now.plusHours(hoursOffset))
            .category(index % 2 == 0 ? "SHIFT" : "OPERATIONS")
            .build();
    }

    // ── Shared helpers ──

    private MedicationAdministrationStatus mapToMarStatus(String status) {
        return switch (status) {
            case "GIVEN" -> MedicationAdministrationStatus.ADMINISTERED;
            case "HELD" -> MedicationAdministrationStatus.HELD;
            case "REFUSED" -> MedicationAdministrationStatus.REFUSED;
            case "MISSED" -> MedicationAdministrationStatus.MISSED;
            default -> MedicationAdministrationStatus.PENDING;
        };
    }

    private Duration normalizeWindow(Duration window) {
        long requestedMinutes = window == null ? DEFAULT_WINDOW.toMinutes() : window.toMinutes();
        long clamped = clampLong(requestedMinutes, 15L, 480L);
        return Duration.ofMinutes(clamped);
    }

    private int clampLimit(Integer requested) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        return clampInt(value, 1, MAX_LIMIT);
    }

    private List<PatientContext> resolvePatientContexts(UUID nurseUserId, UUID hospitalId) {
        List<PatientResponseDTO> patients = resolvePatients(nurseUserId, hospitalId);
        if (patients.isEmpty()) {
            return List.of(new PatientContext(null, SAMPLE_PATIENT_NAME));
        }
        List<PatientContext> contexts = deduplicatePatientContexts(patients);
        if (contexts.isEmpty()) {
            contexts.add(new PatientContext(null, SAMPLE_PATIENT_NAME));
        }
        return contexts;
    }

    private List<PatientContext> deduplicatePatientContexts(List<PatientResponseDTO> patients) {
        List<PatientContext> contexts = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();

        for (PatientResponseDTO patient : patients) {
            UUID patientId = patient.getId();
            if (patientId != null && seenIds.contains(patientId)) {
                continue;
            }

            String name = resolvePatientName(patient);
            if (name == null || name.isBlank()) {
                name = DEFAULT_PATIENT_NAME;
            }

            if (seenNames.contains(name)) {
                int suffix = 2;
                String candidate = name + " #" + suffix;
                while (seenNames.contains(candidate)) {
                    suffix++;
                    candidate = name + " #" + suffix;
                }
                name = candidate;
            }

            contexts.add(new PatientContext(patientId, name));
            if (patientId != null) {
                seenIds.add(patientId);
            }
            seenNames.add(name);
        }

        return contexts;
    }

    private List<PatientResponseDTO> resolvePatients(UUID nurseUserId, UUID hospitalId) {
        if (hospitalId == null) {
            return List.of(createSyntheticPatient());
        }
        List<PatientResponseDTO> patients = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null);
        if (patients.isEmpty()) {
            patients = nurseDashboardService.getPatientsForNurse(null, hospitalId, null);
        }
        if (patients.isEmpty()) {
            patients = List.of(createSyntheticPatient());
        }
        return patients;
    }

    private PatientResponseDTO createSyntheticPatient() {
        return PatientResponseDTO.builder()
            .id(UUID.randomUUID())
            .patientName(SAMPLE_PATIENT_NAME)
            .displayName(SAMPLE_PATIENT_NAME)
            .room("—")
            .build();
    }

    private String resolvePatientName(PatientResponseDTO patient) {
        if (patient.getDisplayName() != null && !patient.getDisplayName().isBlank()) {
            return patient.getDisplayName();
        }
        if (patient.getPatientName() != null && !patient.getPatientName().isBlank()) {
            return patient.getPatientName();
        }
        String first = patient.getFirstName();
        String last = patient.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return full.isEmpty() ? DEFAULT_PATIENT_NAME : full;
    }

    private UUID generateStableId(String patientName, UUID hospitalId, String suffix, int index) {
        String patientSeed = patientName == null ? SEED_PATIENT : patientName;
        String hospitalSeed = hospitalId == null ? DEFAULT_HOSPITAL_SEED : hospitalId.toString();
        String compound = patientSeed + ':' + hospitalSeed + ':' + suffix + ':' + index;
        return UUID.nameUUIDFromBytes(compound.getBytes());
    }

    private String determineMedicationStatus(String filter, LocalDateTime dueTime, LocalDateTime now, int index) {
        if (filter != null && !filter.isBlank()) {
            return filter.trim().toUpperCase(Locale.ROOT);
        }
        if (dueTime.isBefore(now)) {
            return STATUS_OVERDUE;
        }
        return index % 2 == 0 ? STATUS_DUE : STATUS_COMPLETED;
    }

    private String normalizeAdministrationStatus(NurseMedicationAdministrationRequestDTO request) {
        if (request == null || request.getStatus() == null) {
            return DEFAULT_ADMINISTRATION_STATUS;
        }
        String normalized = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return DEFAULT_ADMINISTRATION_STATUS;
        }
        if (!SUPPORTED_ADMINISTRATION_STATUSES.contains(normalized)) {
            throw new BusinessException("Unsupported medication administration status: " + request.getStatus());
        }
        return normalized;
    }

    private String selectVitalType(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "Blood Pressure";
            case 1 -> "Temperature";
            case 2 -> "Respirations";
            default -> "Pulse";
        };
    }

    private String selectMedication(int index) {
        return switch (Math.floorMod(index, 5)) {
            case 0 -> "Lisinopril";
            case 1 -> "Metoprolol";
            case 2 -> "Ceftriaxone";
            case 3 -> "Acetaminophen";
            default -> "Insulin";
        };
    }

    private String selectDosage(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "10 mg";
            case 1 -> "500 mg";
            case 2 -> "2 g";
            default -> "5 units";
        };
    }

    private String selectOrderCategory(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "Lab";
            case 1 -> "Radiology";
            case 2 -> "Consult";
            default -> "Medication";
        };
    }

    private String abbreviateHospitalId(UUID hospitalId) {
        String text = hospitalId.toString();
        return text.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private int clampInt(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private long clampLong(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
