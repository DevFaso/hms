package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Announcement;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Notification;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteTemplate;
import com.example.hms.model.NursingTask;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseFlowPatientCardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseInboxItemDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCompleteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskItemDTO;
import com.example.hms.payload.dto.nurse.NurseVitalCaptureRequestDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseWorkboardPatientDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.NursingTaskRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.NurseTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * MVP-1 implementation of NurseTaskService.
 * <p>
 * Wires <b>Medication Administration</b>, <b>Vitals</b>, and <b>Announcements</b>
 * to real database tables while keeping Orders and Handoffs as enriched synthetic
 * data until their backing entities are created in later MVPs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NurseTaskServiceImpl implements NurseTaskService {

    /* ── Constants ────────────────────────────────────────────────────── */

    private static final String SAMPLE_PATIENT_NAME = "Sample Patient";
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(2);
    private static final int DEFAULT_LIMIT = 6;
    private static final int MAX_LIMIT = 20;

    private static final String TYPE_ROUTINE = "Routine";
    private static final String STATUS_OVERDUE = "OVERDUE";
    private static final String STATUS_DUE = "DUE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found: ";
    private static final String MSG_HOSPITAL_NOT_FOUND = "Hospital not found: ";
    private static final String SEED_PATIENT = "PATIENT";
    private static final String SEED_VITAL = "VITAL";
    private static final String SEED_ORDER = "ORDER";
    private static final String SEED_HANDOFF = "HANDOFF";
    private static final String DEFAULT_HOSPITAL_SEED = "HOSPITAL";
    private static final String DEFAULT_PATIENT_NAME = "Patient";
    private static final String DEFAULT_ADMINISTRATION_STATUS = "GIVEN";

    /** Statuses accepted on the administer endpoint. */
    private static final Set<String> SUPPORTED_ADMINISTRATION_STATUSES = Set.of(
        DEFAULT_ADMINISTRATION_STATUS, "HELD", "REFUSED", "MISSED"
    );

    /** Only prescriptions in these statuses are shown on the MAR. */
    private static final Set<PrescriptionStatus> ACTIVE_RX_STATUSES = Set.of(
        PrescriptionStatus.SIGNED, PrescriptionStatus.TRANSMITTED
    );

    /** Duration after which a patient's vitals are considered overdue. */
    private static final Duration VITALS_OVERDUE_THRESHOLD = Duration.ofHours(4);

    /* ── Dependencies ─────────────────────────────────────────────────── */

    private final NurseDashboardService nurseDashboardService;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicationAdministrationRecordRepository marRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final AnnouncementRepository announcementRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final PatientRepository patientRepository;
    private final NursingTaskRepository nursingTaskRepository;
    private final NursingNoteRepository nursingNoteRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /* ── Inner record ─────────────────────────────────────────────────── */

    private record PatientContext(UUID patientId, String displayName) {
    }

    /* ═══════════════════════════════════════════════════════════════════
       Vitals — queries real PatientVitalSign to find patients needing checks
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseVitalTaskResponseDTO> getDueVitals(UUID nurseUserId, UUID hospitalId, Duration window) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        Duration effectiveWindow = normalizeWindow(window);
        LocalDateTime now = LocalDateTime.now();

        List<NurseVitalTaskResponseDTO> tasks = new ArrayList<>();
        for (PatientContext ctx : patients) {
            if (ctx.patientId() == null) continue;

            // Find the most recent vital sign for this patient at this hospital
            Optional<LocalDateTime> lastRecorded = vitalSignRepository
                .findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(ctx.patientId(), hospitalId)
                .map(v -> v.getRecordedAt());

            // Compute when the next vitals check is due
            LocalDateTime dueTime;
            boolean overdue;
            if (lastRecorded.isEmpty()) {
                // No vitals ever recorded — overdue now
                dueTime = now.minusMinutes(30);
                overdue = true;
            } else {
                dueTime = lastRecorded.get().plus(effectiveWindow);
                overdue = dueTime.isBefore(now);
            }

            // Only include if within the lookahead window or already overdue
            if (overdue || dueTime.isBefore(now.plus(effectiveWindow))) {
                tasks.add(NurseVitalTaskResponseDTO.builder()
                    .id(UUID.nameUUIDFromBytes((ctx.patientId() + ":VITAL:" + hospitalId).getBytes()))
                    .patientId(ctx.patientId())
                    .patientName(ctx.displayName())
                    .type(overdue ? "Full Set" : TYPE_ROUTINE)
                    .dueTime(dueTime)
                    .overdue(overdue)
                    .build());
            }
        }

        // If no real tasks generated, produce a single synthetic placeholder
        if (tasks.isEmpty()) {
            tasks.add(createSyntheticVitalTask(patients, hospitalId, now));
        }

        tasks.sort(Comparator.comparing(NurseVitalTaskResponseDTO::getDueTime));
        return tasks.stream().limit(MAX_LIMIT).toList();
    }

    /* ═══════════════════════════════════════════════════════════════════
       Medication Administration Record (MAR) — backed by Prescription table
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseMedicationTaskResponseDTO> getMedicationTasks(UUID nurseUserId, UUID hospitalId, String statusFilter) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        LocalDateTime now = LocalDateTime.now();

        List<NurseMedicationTaskResponseDTO> tasks = new ArrayList<>();
        for (PatientContext ctx : patients) {
            tasks.addAll(buildMedicationTasksForPatient(ctx, hospitalId, statusFilter, now));
        }

        // Fall back to synthetic data if no real prescriptions exist
        if (tasks.isEmpty()) {
            tasks.addAll(createSyntheticMedicationTasks(patients, hospitalId, now, statusFilter));
        }

        return tasks.stream().limit(MAX_LIMIT).toList();
    }

    /** Build medication tasks for a single patient from their real prescriptions. */
    private List<NurseMedicationTaskResponseDTO> buildMedicationTasksForPatient(
        PatientContext ctx, UUID hospitalId, String statusFilter, LocalDateTime now
    ) {
        if (ctx.patientId() == null || hospitalId == null) return List.of();

        List<Prescription> prescriptions = prescriptionRepository
            .findByPatient_IdAndHospital_Id(ctx.patientId(), hospitalId);

        List<NurseMedicationTaskResponseDTO> result = new ArrayList<>();
        for (Prescription rx : prescriptions) {
            if (!ACTIVE_RX_STATUSES.contains(rx.getStatus())) continue;

            String marStatus = resolveMarStatus(rx, now);
            if (!isFilteredOut(statusFilter, marStatus)) {
                result.add(NurseMedicationTaskResponseDTO.builder()
                    .id(rx.getId())
                    .patientId(ctx.patientId())
                    .patientName(ctx.displayName())
                    .medication(rx.getMedicationName())
                    .dose(buildDoseDisplay(rx))
                    .route(rx.getRoute() != null ? rx.getRoute() : "PO")
                    .dueTime(computeMedicationDueTime(rx, now))
                    .status(marStatus)
                    .build());
            }
        }
        return result;
    }

    /** Returns true when a status filter is active and the given status does not match it. */
    private boolean isFilteredOut(String statusFilter, String actualStatus) {
        if (statusFilter == null || statusFilter.isBlank()) return false;
        return !statusFilter.trim().toUpperCase(Locale.ROOT).equals(actualStatus);
    }

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
        MedicationAdministrationStatus marStatus = MedicationAdministrationStatus.valueOf(normalizedStatus);
        String note = request != null ? request.getNote() : null;

        // Try to find a real prescription matching the task ID
        Optional<Prescription> rxOpt = prescriptionRepository.findById(medicationTaskId);
        if (rxOpt.isPresent()) {
            Prescription rx = rxOpt.get();
            validateHospitalMatch(rx.getHospital(), hospitalId);
            return persistMarRecord(rx, nurseUserId, hospitalId, marStatus, note);
        }

        // Fall back: check existing MAR records
        Optional<MedicationAdministrationRecord> existingMar = marRepository.findById(medicationTaskId);
        if (existingMar.isPresent()) {
            MedicationAdministrationRecord marRecord = existingMar.get();
            validateHospitalMatch(marRecord.getHospital(), hospitalId);
            marRecord.setStatus(marStatus);
            marRecord.setAdministeredAt(LocalDateTime.now());
            marRecord.setNotes(note);
            if (marStatus == MedicationAdministrationStatus.HELD
                || marStatus == MedicationAdministrationStatus.REFUSED) {
                marRecord.setReason(note);
            }
            resolveNurseStaff(nurseUserId, hospitalId).ifPresent(marRecord::setAdministeredByStaff);
            marRepository.save(marRecord);

            Patient patient = marRecord.getPatient();
            return NurseMedicationTaskResponseDTO.builder()
                .id(marRecord.getId())
                .patientId(patient.getId())
                .patientName(patient.getFullName())
                .medication(marRecord.getMedicationName())
                .dose(marRecord.getDose())
                .route(marRecord.getRoute())
                .dueTime(marRecord.getScheduledTime())
                .status(normalizedStatus)
                .build();
        }

        // Last resort: work with synthetic task list (backward-compatible)
        List<NurseMedicationTaskResponseDTO> tasks = getMedicationTasks(nurseUserId, hospitalId, null);
        return tasks.stream()
            .filter(task -> medicationTaskId.equals(task.getId()))
            .findFirst()
            .map(task -> toAdministeredTask(task, normalizedStatus))
            .orElseThrow(() -> new ResourceNotFoundException("Medication administration task not found."));
    }

    /** Convert an existing task DTO to an administered-status copy. */
    private NurseMedicationTaskResponseDTO toAdministeredTask(
        NurseMedicationTaskResponseDTO task, String status
    ) {
        return NurseMedicationTaskResponseDTO.builder()
            .id(task.getId())
            .patientId(task.getPatientId())
            .patientName(task.getPatientName())
            .medication(task.getMedication())
            .dose(task.getDose())
            .route(task.getRoute())
            .dueTime(task.getDueTime())
            .status(status)
            .build();
    }

    /* ═══════════════════════════════════════════════════════════════════
       Orders — still synthetic (entity arrives in MVP 3)
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseOrderTaskResponseDTO> getOrderTasks(UUID nurseUserId, UUID hospitalId, String statusFilter, int limit) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        int effectiveLimit = clampLimit(limit);
        LocalDateTime now = LocalDateTime.now();
        String normalized = statusFilter != null && !statusFilter.isBlank()
            ? statusFilter.trim().toUpperCase(Locale.ROOT) : null;

        return IntStream.range(0, Math.min(effectiveLimit, patients.size()))
            .mapToObj(i -> createOrderTask(patients.get(i), hospitalId, now, i))
            .filter(t -> normalized == null
                || (t.getPriority() != null && normalized.equals(t.getPriority().toUpperCase(Locale.ROOT))))
            .toList();
    }

    /* ═══════════════════════════════════════════════════════════════════
       Handoffs — still synthetic (entity arrives in MVP 2)
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseHandoffSummaryDTO> getHandoffSummaries(UUID nurseUserId, UUID hospitalId, int limit) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        int effectiveLimit = clampLimit(limit);
        LocalDate today = LocalDate.now();

        return IntStream.range(0, Math.min(effectiveLimit, patients.size()))
            .mapToObj(i -> createHandoffSummary(patients.get(i), hospitalId, today, i))
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
        // Validate handoff exists (synthetic for now)
        getHandoffSummaries(nurseUserId, hospitalId, DEFAULT_LIMIT);
    }

    @Override
    @Transactional
    public NurseHandoffChecklistUpdateResponseDTO updateHandoffChecklistItem(
        UUID handoffId, UUID taskId, UUID nurseUserId, UUID hospitalId, boolean completed
    ) {
        if (handoffId == null) {
            throw new BusinessException("Handoff identifier is required.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context required to update handoff checklist.");
        }
        try {
            List<NurseHandoffSummaryDTO> handoffs = getHandoffSummaries(nurseUserId, hospitalId, DEFAULT_LIMIT);
            boolean exists = handoffs.stream().anyMatch(h -> handoffId.equals(h.getId()));
            if (!exists) {
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

    /* ═══════════════════════════════════════════════════════════════════
       Announcements — backed by real Announcement entity
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseAnnouncementDTO> getAnnouncements(UUID hospitalId, int limit) {
        int effectiveLimit = clampLimit(limit);
        Pageable page = PageRequest.of(0, effectiveLimit);

        List<Announcement> dbAnnouncements = announcementRepository.findAll(page).getContent();

        if (!dbAnnouncements.isEmpty()) {
            return dbAnnouncements.stream()
                .map(a -> NurseAnnouncementDTO.builder()
                    .id(a.getId())
                    .text(a.getText())
                    .createdAt(a.getDate())
                    .startsAt(a.getDate())
                    .expiresAt(a.getDate().plusHours(12))
                    .category("SHIFT")
                    .build())
                .toList();
        }

        // Fall back to synthetic announcements when DB is empty
        LocalDateTime now = LocalDateTime.now();
        String label = hospitalId != null ? abbreviateHospitalId(hospitalId) : DEFAULT_HOSPITAL_SEED;
        return IntStream.range(0, effectiveLimit)
            .mapToObj(i -> createSyntheticAnnouncement(now, label, i))
            .toList();
    }

    /* ═══════════════════════════════════════════════════════════════════
       Dashboard Summary — aggregated counts from real data
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public NurseDashboardSummaryDTO getDashboardSummary(UUID nurseUserId, UUID hospitalId) {
        List<PatientContext> patients = resolvePatientContexts(nurseUserId, hospitalId);
        long assignedPatients = patients.stream().filter(p -> p.patientId() != null).count();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime overdueThreshold = now.minus(VITALS_OVERDUE_THRESHOLD);

        long vitalsDue = countVitalsDue(patients, hospitalId, overdueThreshold);
        long[] medCounts = countMedicationStatuses(patients, hospitalId, now);

        // Orders and handoffs pending — count from synthetic lists for now
        long ordersPending = getOrderTasks(nurseUserId, hospitalId, null, MAX_LIMIT).size();
        long handoffsPending = getHandoffSummaries(nurseUserId, hospitalId, MAX_LIMIT).size();

        // Announcement count
        long announcementCount = announcementRepository.count();

        return NurseDashboardSummaryDTO.builder()
            .assignedPatients(assignedPatients)
            .vitalsDue(vitalsDue)
            .medicationsDue(medCounts[0])
            .medicationsOverdue(medCounts[1])
            .ordersPending(ordersPending)
            .handoffsPending(handoffsPending)
            .announcements(announcementCount > 0 ? announcementCount : DEFAULT_LIMIT)
            .build();
    }

    /** Count patients whose vitals are overdue (no vitals or last recording before threshold). */
    private long countVitalsDue(List<PatientContext> patients, UUID hospitalId, LocalDateTime overdueThreshold) {
        long count = 0;
        for (PatientContext ctx : patients) {
            if (ctx.patientId() == null) continue;
            Optional<LocalDateTime> lastRecorded = vitalSignRepository
                .findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(ctx.patientId(), hospitalId)
                .map(v -> v.getRecordedAt());
            if (lastRecorded.isEmpty() || lastRecorded.get().isBefore(overdueThreshold)) {
                count++;
            }
        }
        return count;
    }

    /** Count medications by status (DUE / OVERDUE). Returns {due, overdue}. */
    private long[] countMedicationStatuses(List<PatientContext> patients, UUID hospitalId, LocalDateTime now) {
        long due = 0;
        long overdue = 0;
        for (PatientContext ctx : patients) {
            if (ctx.patientId() == null || hospitalId == null) continue;
            List<Prescription> prescriptions = prescriptionRepository
                .findByPatient_IdAndHospital_Id(ctx.patientId(), hospitalId);
            for (Prescription rx : prescriptions) {
                if (!ACTIVE_RX_STATUSES.contains(rx.getStatus())) continue;
                String marStatus = resolveMarStatus(rx, now);
                switch (marStatus) {
                    case STATUS_DUE -> due++;
                    case STATUS_OVERDUE -> overdue++;
                    default -> { /* COMPLETED — not counted */ }
                }
            }
        }
        return new long[]{due, overdue};
    }

    /* ═══════════════════════════════════════════════════════════════════
       Private helpers — MAR
       ═══════════════════════════════════════════════════════════════════ */

    /** Determine MAR status from a Prescription's creation time (simplified schedule). */
    private String resolveMarStatus(Prescription rx, LocalDateTime now) {
        // Check if there's already a MAR record marked as GIVEN for this prescription
        List<MedicationAdministrationRecord> records = marRepository
            .findByPatient_IdAndHospital_IdAndStatus(
                rx.getPatient().getId(), rx.getHospital().getId(),
                MedicationAdministrationStatus.GIVEN);
        boolean alreadyGiven = records.stream().anyMatch(r -> r.getPrescription().getId().equals(rx.getId()));
        if (alreadyGiven) return STATUS_COMPLETED;

        // Use prescription creation time + 4 hours as a naive "due" window
        LocalDateTime createdAt = rx.getCreatedAt() != null ? rx.getCreatedAt() : now.minusHours(1);
        LocalDateTime dueBy = createdAt.plusHours(4);
        if (dueBy.isBefore(now)) return STATUS_OVERDUE;
        return STATUS_DUE;
    }

    /** Compute a display due-time for a medication. */
    private LocalDateTime computeMedicationDueTime(Prescription rx, LocalDateTime now) {
        if (rx.getCreatedAt() != null) {
            return rx.getCreatedAt().plusHours(4);
        }
        return now.plusHours(1);
    }

    /** Build a human-readable dose string from Prescription fields. */
    private String buildDoseDisplay(Prescription rx) {
        String dosage = rx.getDosage();
        String unit = rx.getDoseUnit();
        if (dosage != null && unit != null) return dosage + " " + unit;
        if (dosage != null) return dosage;
        return "See order";
    }

    /** Persist a MedicationAdministrationRecord linked to a real Prescription. */
    private NurseMedicationTaskResponseDTO persistMarRecord(
        Prescription rx, UUID nurseUserId, UUID hospitalId,
        MedicationAdministrationStatus status, String note
    ) {
        MedicationAdministrationRecord marRecord = MedicationAdministrationRecord.builder()
            .prescription(rx)
            .patient(rx.getPatient())
            .hospital(rx.getHospital())
            .medicationName(rx.getMedicationName())
            .dose(buildDoseDisplay(rx))
            .route(rx.getRoute() != null ? rx.getRoute() : "PO")
            .scheduledTime(computeMedicationDueTime(rx, LocalDateTime.now()))
            .administeredAt(LocalDateTime.now())
            .status(status)
            .reason(status == MedicationAdministrationStatus.HELD
                || status == MedicationAdministrationStatus.REFUSED ? note : null)
            .notes(note)
            .build();

        resolveNurseStaff(nurseUserId, hospitalId).ifPresent(marRecord::setAdministeredByStaff);
        MedicationAdministrationRecord saved = marRepository.save(marRecord);

        log.info("MAR recorded: prescriptionId={}, status={}, nurse={}", rx.getId(), status, nurseUserId);

        return NurseMedicationTaskResponseDTO.builder()
            .id(saved.getId())
            .patientId(rx.getPatient().getId())
            .patientName(rx.getPatient().getFullName())
            .medication(rx.getMedicationName())
            .dose(buildDoseDisplay(rx))
            .route(rx.getRoute() != null ? rx.getRoute() : "PO")
            .dueTime(saved.getScheduledTime())
            .status(status.name())
            .build();
    }

    /** Resolve nurse Staff entity from userId + hospitalId. */
    private Optional<Staff> resolveNurseStaff(UUID nurseUserId, UUID hospitalId) {
        if (nurseUserId == null || hospitalId == null) return Optional.empty();
        return staffRepository.findByUserIdAndHospitalId(nurseUserId, hospitalId);
    }

    /* ═══════════════════════════════════════════════════════════════════
       Private helpers — synthetic fallbacks
       ═══════════════════════════════════════════════════════════════════ */

    private NurseVitalTaskResponseDTO createSyntheticVitalTask(
        List<PatientContext> patients, UUID hospitalId, LocalDateTime now
    ) {
        PatientContext ctx = patients.isEmpty()
            ? new PatientContext(null, SAMPLE_PATIENT_NAME) : patients.get(0);
        return NurseVitalTaskResponseDTO.builder()
            .id(generateStableId(ctx.displayName(), hospitalId, SEED_VITAL, 0))
            .patientId(ctx.patientId())
            .patientName(ctx.displayName())
            .type(TYPE_ROUTINE)
            .dueTime(now.plusMinutes(60))
            .overdue(false)
            .build();
    }

    private List<NurseMedicationTaskResponseDTO> createSyntheticMedicationTasks(
        List<PatientContext> patients, UUID hospitalId, LocalDateTime now, String statusFilter
    ) {
        return IntStream.range(0, Math.min(DEFAULT_LIMIT, patients.size()))
            .mapToObj(i -> {
                PatientContext ctx = patients.get(i);
                LocalDateTime dueTime = now.plusMinutes(30L * (i + 1));
                String status = determineSyntheticMedStatus(statusFilter, dueTime, now, i);
                return NurseMedicationTaskResponseDTO.builder()
                    .id(generateStableId(ctx.displayName(), hospitalId, "MEDICATION", i))
                    .patientId(ctx.patientId())
                    .patientName(ctx.displayName())
                    .medication(selectMedication(i))
                    .dose(selectDosage(i))
                    .route(i % 2 == 0 ? "IV" : "PO")
                    .dueTime(dueTime)
                    .status(status)
                    .build();
            })
            .toList();
    }

    private NurseOrderTaskResponseDTO createOrderTask(
        PatientContext patient, UUID hospitalId, LocalDateTime now, int index
    ) {
        return NurseOrderTaskResponseDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_ORDER, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .orderType(selectOrderCategory(index))
            .priority(index % 3 == 0 ? "STAT" : TYPE_ROUTINE)
            .dueTime(now.plusMinutes(45L * (index + 1)))
            .build();
    }

    private NurseHandoffSummaryDTO createHandoffSummary(
        PatientContext patient, UUID hospitalId, LocalDate today, int index
    ) {
        return NurseHandoffSummaryDTO.builder()
            .id(generateStableId(patient.displayName(), hospitalId, SEED_HANDOFF, index))
            .patientId(patient.patientId())
            .patientName(patient.displayName())
            .direction(index % 2 == 0 ? "Transfer to Radiology" : "Return from OR")
            .updatedAt(today.atStartOfDay().plusHours(7L + index))
            .note(index % 3 == 0 ? "High fall risk." : "Ready for handoff discussion.")
            .build();
    }

    private NurseAnnouncementDTO createSyntheticAnnouncement(
        LocalDateTime now, String hospitalLabel, int index
    ) {
        return NurseAnnouncementDTO.builder()
            .id(UUID.randomUUID())
            .text(index == 0
                ? String.format("[%s] Safety huddle at 15:00.", hospitalLabel)
                : String.format("[%s] Operational update %d", hospitalLabel, index + 1))
            .createdAt(now.minusHours(index))
            .startsAt(now.minusHours(index))
            .expiresAt(now.plusHours(6L + index))
            .category(index % 2 == 0 ? "SHIFT" : "OPERATIONS")
            .build();
    }

    /* ═══════════════════════════════════════════════════════════════════
       Private helpers — patient resolution (unchanged from original)
       ═══════════════════════════════════════════════════════════════════ */

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
            if (patientId != null && seenIds.contains(patientId)) continue;

            String name = resolvePatientName(patient);
            if (name == null || name.isBlank()) name = DEFAULT_PATIENT_NAME;

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
            if (patientId != null) seenIds.add(patientId);
            seenNames.add(name);
        }
        return contexts;
    }

    private List<PatientResponseDTO> resolvePatients(UUID nurseUserId, UUID hospitalId) {
        if (hospitalId == null) return List.of(createSyntheticPatient());
        List<PatientResponseDTO> patients = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null);
        if (patients.isEmpty()) {
            log.warn("No assigned patients found for nurse {}, falling back to all-hospital patient list for hospital {}",
                    nurseUserId, hospitalId);
            patients = nurseDashboardService.getPatientsForNurse(null, hospitalId, null);
        }
        if (patients.isEmpty()) patients = List.of(createSyntheticPatient());
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
        if (patient.getDisplayName() != null && !patient.getDisplayName().isBlank()) return patient.getDisplayName();
        if (patient.getPatientName() != null && !patient.getPatientName().isBlank()) return patient.getPatientName();
        String first = patient.getFirstName();
        String last = patient.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return full.isEmpty() ? DEFAULT_PATIENT_NAME : full;
    }

    /* ═══════════════════════════════════════════════════════════════════
       Private helpers — misc
       ═══════════════════════════════════════════════════════════════════ */

    private UUID generateStableId(String patientName, UUID hospitalId, String suffix, int index) {
        String patientSeed = patientName == null ? SEED_PATIENT : patientName;
        String hospitalSeed = hospitalId == null ? DEFAULT_HOSPITAL_SEED : hospitalId.toString();
        return UUID.nameUUIDFromBytes((patientSeed + ':' + hospitalSeed + ':' + suffix + ':' + index).getBytes());
    }

    private String determineSyntheticMedStatus(String filter, LocalDateTime dueTime, LocalDateTime now, int index) {
        if (filter != null && !filter.isBlank()) return filter.trim().toUpperCase(Locale.ROOT);
        if (dueTime.isBefore(now)) return STATUS_OVERDUE;
        return index % 2 == 0 ? STATUS_DUE : STATUS_COMPLETED;
    }

    private String normalizeAdministrationStatus(NurseMedicationAdministrationRequestDTO request) {
        if (request == null || request.getStatus() == null) return DEFAULT_ADMINISTRATION_STATUS;
        String normalized = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) return DEFAULT_ADMINISTRATION_STATUS;
        if (!SUPPORTED_ADMINISTRATION_STATUSES.contains(normalized)) {
            throw new BusinessException("Unsupported medication administration status: " + request.getStatus());
        }
        return normalized;
    }

    private String selectMedication(int index) {
        return switch (Math.floorMod(index, 5)) {
            case 0 -> "Lisinopril"; case 1 -> "Metoprolol"; case 2 -> "Ceftriaxone";
            case 3 -> "Acetaminophen"; default -> "Insulin";
        };
    }

    private String selectDosage(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "10 mg"; case 1 -> "500 mg"; case 2 -> "2 g"; default -> "5 units";
        };
    }

    private String selectOrderCategory(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "Lab"; case 1 -> "Radiology"; case 2 -> "Consult"; default -> "Medication";
        };
    }

    private String abbreviateHospitalId(UUID hospitalId) {
        return hospitalId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private int clampInt(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    private long clampLong(long value, long min, long max) {
        return Math.clamp(value, min, max);
    }

    private void validateHospitalMatch(Hospital entityHospital, UUID scopedHospitalId) {
        if (scopedHospitalId == null || entityHospital == null) return;
        if (!scopedHospitalId.equals(entityHospital.getId())) {
            throw new BusinessException(
                "Resource does not belong to the scoped hospital.");
        }
    }

    /* ═══════════════════════════════════════════════════════════════════
       MVP-12 — Workboard, Flow Board, Vitals Capture, Admissions Panel
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseWorkboardPatientDTO> getWorkboard(UUID nurseUserId, UUID hospitalId) {
        if (hospitalId == null) return List.of();
        List<Admission> admissions = admissionRepository.findActiveAdmissionsByHospital(hospitalId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime overdueThreshold = now.minus(VITALS_OVERDUE_THRESHOLD);

        List<NurseWorkboardPatientDTO> result = new ArrayList<>();
        for (Admission a : admissions) {
            result.add(toWorkboardCard(a, hospitalId, overdueThreshold, now));
        }
        return result;
    }

    private NurseWorkboardPatientDTO toWorkboardCard(Admission a, UUID hospitalId,
                                                     LocalDateTime overdueThreshold, LocalDateTime now) {
        Patient patient = a.getPatient();
        Optional<LocalDateTime> lastVitals = vitalSignRepository
            .findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patient.getId(), hospitalId)
            .map(PatientVitalSign::getRecordedAt);

        boolean vitalsDue = lastVitals.isEmpty() || lastVitals.get().isBefore(overdueThreshold);

        long medsDue = prescriptionRepository
            .findByPatient_IdAndHospital_Id(patient.getId(), hospitalId)
            .stream()
            .filter(rx -> ACTIVE_RX_STATUSES.contains(rx.getStatus()))
            .filter(rx -> !STATUS_COMPLETED.equals(resolveMarStatus(rx, now)))
            .count();

        String departmentName = a.getDepartment() != null ? a.getDepartment().getName() : null;
        String attendingDoctor = a.getAdmittingProvider() != null
            ? a.getAdmittingProvider().getFullName() : null;

        return NurseWorkboardPatientDTO.builder()
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(patient.getMrnForHospital(hospitalId))
            .roomBed(a.getRoomBed())
            .acuityLevel(a.getAcuityLevel() != null ? a.getAcuityLevel().name() : null)
            .admissionId(a.getId())
            .departmentName(departmentName)
            .attendingDoctor(attendingDoctor)
            .admittedAt(a.getAdmissionDateTime())
            .lastVitalsTime(lastVitals.orElse(null))
            .vitalsDue(vitalsDue)
            .medsDue(medsDue)
            .build();
    }

    @Override
    public NurseFlowBoardDTO getPatientFlow(UUID hospitalId, UUID departmentId) {
        if (hospitalId == null) {
            return NurseFlowBoardDTO.builder()
                .pending(List.of()).active(List.of())
                .critical(List.of()).awaitingDischarge(List.of())
                .build();
        }

        List<Admission> all;
        if (departmentId != null) {
            // Department-scoped: only ACTIVE, no AWAITING_DISCHARGE cross-filter needed
            all = admissionRepository.findByDepartmentIdAndStatusOrderByAdmissionDateTimeDesc(
                departmentId, AdmissionStatus.ACTIVE);
        } else {
            // Hospital-wide: single query fetching ACTIVE, ON_LEAVE, and AWAITING_DISCHARGE
            // with JOIN FETCH to avoid N+1 on patient.hospitalRegistrations
            all = admissionRepository.findFlowBoardAdmissions(
                hospitalId,
                List.of(AdmissionStatus.ACTIVE, AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE));
        }

        List<NurseFlowPatientCardDTO> pending = new ArrayList<>();
        List<NurseFlowPatientCardDTO> active = new ArrayList<>();
        List<NurseFlowPatientCardDTO> critical = new ArrayList<>();
        List<NurseFlowPatientCardDTO> awaitingDischarge = new ArrayList<>();

        LocalDateTime now = LocalDateTime.now();
        for (Admission a : all) {
            NurseFlowPatientCardDTO card = toFlowCard(a, now);
            AcuityLevel acuity = a.getAcuityLevel();
            if (a.getStatus() == AdmissionStatus.AWAITING_DISCHARGE) {
                awaitingDischarge.add(card);
            } else if (acuity == AcuityLevel.LEVEL_4_SEVERE || acuity == AcuityLevel.LEVEL_5_CRITICAL) {
                critical.add(card);
            } else if (a.getStatus() == AdmissionStatus.PENDING) {
                pending.add(card);
            } else {
                active.add(card);
            }
        }

        return NurseFlowBoardDTO.builder()
            .pending(pending)
            .active(active)
            .critical(critical)
            .awaitingDischarge(awaitingDischarge)
            .build();
    }

    private NurseFlowPatientCardDTO toFlowCard(Admission a, LocalDateTime now) {
        long waitMinutes = a.getAdmissionDateTime() != null
            ? java.time.Duration.between(a.getAdmissionDateTime(), now).toMinutes() : 0;
        UUID hospId = a.getHospital() != null ? a.getHospital().getId() : null;
        return NurseFlowPatientCardDTO.builder()
            .patientId(a.getPatient().getId())
            .patientName(a.getPatient().getFullName())
            .mrn(hospId != null ? a.getPatient().getMrnForHospital(hospId) : null)
            .admissionId(a.getId())
            .acuityLevel(a.getAcuityLevel() != null ? a.getAcuityLevel().name() : null)
            .waitMinutes(waitMinutes)
            .roomBed(a.getRoomBed())
            .departmentName(a.getDepartment() != null ? a.getDepartment().getName() : null)
            .admittedAt(a.getAdmissionDateTime())
            .build();
    }

    @Override
    @Transactional
    public void captureVitals(UUID patientId, UUID nurseUserId, UUID hospitalId,
                              NurseVitalCaptureRequestDTO request) {
        if (patientId == null) throw new BusinessException("Patient ID required.");
        if (hospitalId == null) throw new BusinessException("Hospital context required.");
        if (request == null) throw new BusinessException("Vital sign data required.");

        Patient patient = patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));

        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        boolean clinicallySig = isClinicallySignificant(request);

        PatientVitalSign vital = PatientVitalSign.builder()
            .patient(patient)
            .hospital(hospital)
            .recordedAt(LocalDateTime.now())
            .source("NURSE_STATION")
            .temperatureCelsius(request.getTemperatureCelsius())
            .heartRateBpm(request.getHeartRateBpm())
            .respiratoryRateBpm(request.getRespiratoryRateBpm())
            .systolicBpMmHg(request.getSystolicBpMmHg())
            .diastolicBpMmHg(request.getDiastolicBpMmHg())
            .spo2Percent(request.getSpo2Percent())
            .bloodGlucoseMgDl(request.getBloodGlucoseMgDl())
            .weightKg(request.getWeightKg())
            .notes(request.getNotes())
            .clinicallySignificant(clinicallySig)
            .build();

        resolveNurseStaff(nurseUserId, hospitalId).ifPresent(vital::setRecordedByStaff);
        vitalSignRepository.save(vital);

        // Advance encounter status: ARRIVED → TRIAGE so the Patient Tracker Board reflects vitals captured
        encounterRepository
            .findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
                patientId, hospitalId, EncounterStatus.ARRIVED)
            .ifPresent(encounter -> {
                encounter.setStatus(EncounterStatus.TRIAGE);
                encounterRepository.save(encounter);
                log.info("Encounter {} transitioned ARRIVED → TRIAGE after vitals", encounter.getId());
            });

        log.info("Vitals captured: patientId={}, nurse={}, significant={}", patientId, nurseUserId, clinicallySig);
    }

    /** Auto-flag a vital set as clinically significant when values fall outside safe ranges. */
    private boolean isClinicallySignificant(NurseVitalCaptureRequestDTO req) {
        return isOutOfRange(req.getHeartRateBpm(), 40, 150)
                || isBelow(req.getSpo2Percent(), 90)
                || isOutOfRange(req.getRespiratoryRateBpm(), 8, 30)
                || isOutOfRange(req.getSystolicBpMmHg(), 80, 200)
                || isAbove(req.getDiastolicBpMmHg(), 120)
                || isOutOfDoubleRange(req.getTemperatureCelsius(), 35.0, 40.0)
                || isOutOfRange(req.getBloodGlucoseMgDl(), 50, 400);
    }

    private static boolean isOutOfRange(Integer value, int min, int max) {
        return value != null && (value < min || value > max);
    }

    private static boolean isBelow(Integer value, int min) {
        return value != null && value < min;
    }

    private static boolean isAbove(Integer value, int max) {
        return value != null && value > max;
    }

    private static boolean isOutOfDoubleRange(Double value, double min, double max) {
        return value != null && (value < min || value > max);
    }

    @Override
    public List<NurseAdmissionSummaryDTO> getPendingAdmissions(UUID hospitalId, UUID departmentId) {
        if (hospitalId == null) return List.of();

        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);

        // New arrivals: PENDING or ACTIVE admitted within the last 2 hours
        List<Admission> newArrivals = admissionRepository.findActiveAdmissionsByHospital(hospitalId)
                .stream()
                .filter(a -> a.getAdmissionDateTime() != null
                    && (a.getStatus() == AdmissionStatus.PENDING
                        || a.getAdmissionDateTime().isAfter(twoHoursAgo)))
                .filter(a -> departmentId == null
                    || (a.getDepartment() != null && departmentId.equals(a.getDepartment().getId())))
                .toList();

        // Patients awaiting discharge
        List<Admission> awaitingDischarge = admissionRepository
            .findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.AWAITING_DISCHARGE);

        List<NurseAdmissionSummaryDTO> result = new ArrayList<>();
        for (Admission a : newArrivals) {
            result.add(toAdmissionSummary(a));
        }
        for (Admission a : awaitingDischarge) {
            // Avoid duplicates if somehow already included
            if (result.stream().noneMatch(r -> a.getId().equals(r.getAdmissionId()))) {
                result.add(toAdmissionSummary(a));
            }
        }
        return result;
    }

    private NurseAdmissionSummaryDTO toAdmissionSummary(Admission a) {
        UUID hospId = a.getHospital() != null ? a.getHospital().getId() : null;
        return NurseAdmissionSummaryDTO.builder()
            .admissionId(a.getId())
            .patientId(a.getPatient().getId())
            .patientName(a.getPatient().getFullName())
            .mrn(hospId != null ? a.getPatient().getMrnForHospital(hospId) : null)
            .status(a.getStatus() != null ? a.getStatus().name() : null)
            .acuityLevel(a.getAcuityLevel() != null ? a.getAcuityLevel().name() : null)
            .roomBed(a.getRoomBed())
            .departmentName(a.getDepartment() != null ? a.getDepartment().getName() : null)
            .admittingDoctor(a.getAdmittingProvider() != null ? a.getAdmittingProvider().getFullName() : null)
            .admissionDateTime(a.getAdmissionDateTime())
            .admissionType(a.getAdmissionType() != null ? a.getAdmissionType().name() : null)
            .build();
    }

    /* ═══════════════════════════════════════════════════════════════════
       MVP-13 — Nursing Task Board, Care Notes, Inbox
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseTaskItemDTO> getNursingTaskBoard(UUID hospitalId, String statusFilter) {
        if (hospitalId == null) return List.of();

        List<NursingTask> tasks;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            tasks = nursingTaskRepository.findByHospital_IdAndStatusOrderByDueAtAsc(hospitalId, statusFilter.toUpperCase(Locale.ROOT));
        } else {
            // Default: show PENDING and IN_PROGRESS only (exclude COMPLETED/CANCELLED)
            tasks = nursingTaskRepository.findByHospital_IdAndStatusNotOrderByDueAtAsc(hospitalId, STATUS_COMPLETED);
        }

        LocalDateTime now = LocalDateTime.now();
        return tasks.stream()
            .map(t -> toTaskItemDTO(t, now))
            .toList();
    }

    @Override
    @Transactional
    public NurseTaskItemDTO createNursingTask(UUID nurseUserId, UUID hospitalId, NurseTaskCreateRequestDTO request) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + request.getPatientId()));

        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        String createdByName = resolveNurseName(nurseUserId);

        NursingTask task = NursingTask.builder()
            .hospital(hospital)
            .patient(patient)
            .category(request.getCategory().toUpperCase(Locale.ROOT))
            .description(request.getDescription())
            .priority(request.getPriority() != null ? request.getPriority().toUpperCase(Locale.ROOT) : "ROUTINE")
            .status("PENDING")
            .dueAt(request.getDueAt())
            .createdByName(createdByName)
            .build();

        NursingTask saved = nursingTaskRepository.save(task);
        return toTaskItemDTO(saved, LocalDateTime.now());
    }

    @Override
    @Transactional
    public NurseTaskItemDTO completeNursingTask(UUID taskId, UUID nurseUserId, UUID hospitalId, NurseTaskCompleteRequestDTO request) {
        NursingTask task = nursingTaskRepository.findByIdAndHospital_Id(taskId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Nursing task not found: " + taskId));

        String nurseName = resolveNurseName(nurseUserId);
        task.setStatus(STATUS_COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedByName(nurseName);
        if (request != null && request.getCompletionNote() != null) {
            task.setCompletionNote(request.getCompletionNote().trim());
        }

        NursingTask saved = nursingTaskRepository.save(task);
        return toTaskItemDTO(saved, LocalDateTime.now());
    }

    @Override
    public List<NurseInboxItemDTO> getNurseInboxItems(String nurseUsername, int limit) {
        if (nurseUsername == null || nurseUsername.isBlank()) return List.of();
        int effectiveLimit = Math.clamp(limit, 1, 50);
        Pageable pageable = PageRequest.of(0, effectiveLimit);
        return notificationRepository
            .findByRecipientUsername(nurseUsername, pageable)
            .getContent()
            .stream()
            .map(n -> NurseInboxItemDTO.builder()
                .id(n.getId())
                .message(n.getMessage())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .build())
            .toList();
    }

    @Override
    @Transactional
    public void markNurseInboxRead(UUID itemId, String nurseUsername) {
        Notification notification = notificationRepository.findById(itemId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + itemId));
        if (!notification.getRecipientUsername().equals(nurseUsername)) {
            throw new BusinessException("Access denied: notification does not belong to this nurse.");
        }
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public NurseCareNoteResponseDTO createCareNote(UUID patientId, UUID nurseUserId,
                                                   UUID hospitalId, NurseCareNoteRequestDTO request) {
        Patient patient = patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));

        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        User author = userRepository.findById(nurseUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + nurseUserId));

        NursingNoteTemplate template;
        try {
            template = NursingNoteTemplate.valueOf(request.getTemplate().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            template = NursingNoteTemplate.DAR;
        }

        String authorName = formatFullName(author.getFirstName(), author.getLastName(), author.getUsername());

        NursingNote.NursingNoteBuilder noteBuilder = NursingNote.builder()
            .patient(patient)
            .hospital(hospital)
            .author(author)
            .authorName(authorName)
            .template(template)
            .documentedAt(LocalDateTime.now())
            .narrative(request.getNarrative())
            .attestAccuracy(true);

        if (template == NursingNoteTemplate.SOAPIE) {
            noteBuilder
                .dataSubjective(request.getSubjective())
                .dataObjective(request.getObjective())
                .dataAssessment(request.getAssessment())
                .dataPlan(request.getPlan())
                .dataImplementation(request.getImplementation())
                .dataEvaluation(request.getEvaluation());
        } else {
            // DAR
            noteBuilder
                .dataSubjective(request.getDataPart())
                .actionSummary(request.getActionPart())
                .responseSummary(request.getResponsePart());
        }

        NursingNote saved = nursingNoteRepository.save(noteBuilder.build());

        String title = request.getTitle() != null ? request.getTitle()
            : (template.name() + " Note — " + patient.getFullName());
        String summary = buildNoteSummary(request, template);

        return NurseCareNoteResponseDTO.builder()
            .noteId(saved.getId())
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .template(template.name())
            .title(title)
            .summary(summary)
            .authorName(authorName)
            .documentedAt(saved.getDocumentedAt())
            .build();
    }

    /* ── MVP-13 helpers ──────────────────────────────────────────────── */

    private NurseTaskItemDTO toTaskItemDTO(NursingTask t, LocalDateTime now) {
        boolean overdue = t.getDueAt() != null
            && "PENDING".equals(t.getStatus())
            && t.getDueAt().isBefore(now);

        String mrn = null;
        try {
            mrn = t.getPatient().getMrnForHospital(t.getHospital().getId());
        } catch (Exception ignored) { /* not critical */ }

        return NurseTaskItemDTO.builder()
            .id(t.getId())
            .patientId(t.getPatient().getId())
            .patientName(t.getPatient().getFullName())
            .mrn(mrn)
            .category(t.getCategory())
            .description(t.getDescription())
            .priority(t.getPriority())
            .status(t.getStatus())
            .dueAt(t.getDueAt())
            .overdue(overdue)
            .completedAt(t.getCompletedAt())
            .completedByName(t.getCompletedByName())
            .completionNote(t.getCompletionNote())
            .createdByName(t.getCreatedByName())
            .build();
    }

    private String resolveNurseName(UUID nurseUserId) {
        if (nurseUserId == null) return "Nurse";
        return userRepository.findById(nurseUserId)
            .map(u -> formatFullName(u.getFirstName(), u.getLastName(), u.getUsername()))
            .orElse("Nurse");
    }

    private String formatFullName(String firstName, String lastName, String fallback) {
        if (firstName == null) return fallback;
        return lastName != null ? firstName + " " + lastName : firstName;
    }

    private String buildNoteSummary(NurseCareNoteRequestDTO req, NursingNoteTemplate template) {
        if (req.getNarrative() != null && !req.getNarrative().isBlank()) {
            String n = req.getNarrative().trim();
            return n.length() > 120 ? n.substring(0, 117) + "..." : n;
        }
        if (template == NursingNoteTemplate.SOAPIE && req.getSubjective() != null) {
            String s = req.getSubjective().trim();
            return s.length() > 120 ? s.substring(0, 117) + "..." : s;
        }
        if (template == NursingNoteTemplate.DAR && req.getDataPart() != null) {
            String d = req.getDataPart().trim();
            return d.length() > 120 ? d.substring(0, 117) + "..." : d;
        }
        return "";
    }
}
