package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.FiveRightsCheck;
import com.example.hms.enums.FiveRightsStatus;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Announcement;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Notification;
import com.example.hms.model.NurseHandoff;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteTemplate;
import com.example.hms.model.NursingTask;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.MarVerificationRequestDTO;
import com.example.hms.payload.dto.nurse.MarVerificationResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseFlowPatientCardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffCreateRequestDTO;
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
import com.example.hms.persistence.JpaProxyUtils;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.NurseHandoffRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.NursingTaskRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.NurseTaskService;
import com.example.hms.service.emar.FiveRightsVerificationResult;
import com.example.hms.service.emar.FiveRightsVerificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of NurseTaskService.
 * <p>
 * Every queue is backed by real data: vitals, MAR, and announcements query
 * their own tables; the order queue is derived from pending lab/imaging/
 * procedure orders; handoffs are persisted {@link NurseHandoff} SBAR records
 * (P0 #1 — the previous synthetic order/handoff generators are gone).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NurseTaskServiceImpl implements NurseTaskService {

    /* ── Constants ────────────────────────────────────────────────────── */

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(2);
    private static final int DEFAULT_LIMIT = 6;
    private static final int MAX_LIMIT = 20;

    private static final String TYPE_ROUTINE = "Routine";
    private static final String STATUS_OVERDUE = "OVERDUE";
    private static final String STATUS_DUE = "DUE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found: ";
    private static final String MSG_HOSPITAL_NOT_FOUND = "Hospital not found: ";
    private static final String DEFAULT_PATIENT_NAME = "Patient";
    private static final String DEFAULT_ADMINISTRATION_STATUS = "GIVEN";
    private static final String ADMISSION_OWNER = "Admission";
    private static final String ASSOCIATION_PATIENT = "patient";
    private static final String ASSOCIATION_DEPARTMENT = "department";
    private static final String PRIORITY_ROUTINE = "ROUTINE";

    /** How many pending handoff rows to scan when filtering by assignee. */
    private static final int HANDOFF_SCAN_PAGE = 100;

    /** Lab orders where the specimen has not been collected yet — nursing action. */
    private static final Set<LabOrderStatus> NURSE_ACTION_LAB_STATUSES =
        Set.of(LabOrderStatus.ORDERED, LabOrderStatus.PENDING);
    /** Imaging orders still in the prep phase (NPO, consent, transport). */
    private static final List<ImagingOrderStatus> NURSE_ACTION_IMAGING_STATUSES =
        List.of(ImagingOrderStatus.ORDERED, ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED);
    /** Procedure orders not yet under way — consent, site marking, pre-op prep. */
    private static final Set<ProcedureOrderStatus> NURSE_ACTION_PROCEDURE_STATUSES =
        Set.of(ProcedureOrderStatus.ORDERED, ProcedureOrderStatus.SCHEDULED,
            ProcedureOrderStatus.PRE_OP_CLEARANCE_PENDING);

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
    private final NurseHandoffRepository nurseHandoffRepository;
    private final LabOrderRepository labOrderRepository;
    private final ImagingOrderRepository imagingOrderRepository;
    private final ProcedureOrderRepository procedureOrderRepository;
    private final FiveRightsVerificationService fiveRightsVerificationService;
    private final ObjectMapper objectMapper;

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
        String overrideReason = request != null ? request.getOverrideReason() : null;

        // Try to find a real prescription matching the task ID
        Optional<Prescription> rxOpt = prescriptionRepository.findById(medicationTaskId);
        if (rxOpt.isPresent()) {
            Prescription rx = rxOpt.get();
            validateHospitalMatch(rx.getHospital(), hospitalId);
            return persistMarRecord(rx, nurseUserId, hospitalId, marStatus, note, overrideReason);
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
            recordOverrideOnAdminister(marRecord, marStatus, overrideReason);
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

    /* ═══════════════════════════════════════════════════════════════════
       eMAR five-rights verification (P1 #8)
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public MarVerificationResponseDTO verifyMedicationAdministration(
        UUID marId, UUID nurseUserId, UUID hospitalId, MarVerificationRequestDTO request
    ) {
        if (marId == null) {
            throw new BusinessException("MAR identifier is required for verification.");
        }
        if (request == null) {
            throw new BusinessException("Verification request body is required.");
        }

        MedicationAdministrationRecord mar = loadOrMaterializeMar(marId, nurseUserId, hospitalId);
        validateHospitalMatch(mar.getHospital(), hospitalId);

        FiveRightsVerificationResult result = fiveRightsVerificationService.verify(
            mar,
            request.getPatientScanValue(),
            request.getMedicationScanValue(),
            request.getDoseScanValue(),
            request.getRouteScanValue(),
            request.getAdministeredAt()
        );

        LocalDateTime verifiedAt = LocalDateTime.now();
        mar.setPatientScanValue(request.getPatientScanValue());
        mar.setMedicationScanValue(request.getMedicationScanValue());
        mar.setDoseScanValue(request.getDoseScanValue());
        mar.setRouteScanValue(request.getRouteScanValue());
        mar.setScanVerifiedAt(verifiedAt);
        mar.setFiveRightsStatus(result.allPassed() ? FiveRightsStatus.VERIFIED : FiveRightsStatus.NOT_VERIFIED);
        // A new verification supersedes any prior override decision: clear
        // both the JSON override list and the free-text reason so a stale
        // OVERRIDDEN reason from an earlier attempt cannot survive a clean
        // re-verify.
        mar.setFiveRightsOverrides(null);
        mar.setOverrideReason(null);

        resolveNurseStaff(nurseUserId, hospitalId).ifPresent(mar::setAdministeredByStaff);
        marRepository.save(mar);

        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        result.getOutcomes().forEach((check, ok) -> outcomes.put(check.name(), ok));

        Map<String, String> reasons = new LinkedHashMap<>();
        result.getFailureReasons().forEach((check, reason) -> reasons.put(check.name(), reason));

        List<String> failed = result.failedChecks().stream().map(Enum::name).toList();

        return MarVerificationResponseDTO.builder()
            .marId(mar.getId())
            .outcomes(outcomes)
            .failedChecks(failed)
            .failureReasons(reasons)
            .allPassed(result.allPassed())
            .verifiedAt(verifiedAt)
            .build();
    }

    /**
     * Resolve a MAR row by id, materialising one from a Prescription if the id
     * still points at the synthetic prescription-as-task identifier the MAR
     * list endpoint emits before the first administration is recorded.
     */
    private MedicationAdministrationRecord loadOrMaterializeMar(
        UUID marId, UUID nurseUserId, UUID hospitalId
    ) {
        Optional<MedicationAdministrationRecord> existing = marRepository.findById(marId);
        if (existing.isPresent()) return existing.get();

        Prescription rx = prescriptionRepository.findById(marId)
            .orElseThrow(() -> new ResourceNotFoundException("MAR record not found: " + marId));
        validateHospitalMatch(rx.getHospital(), hospitalId);

        MedicationAdministrationRecord seeded = MedicationAdministrationRecord.builder()
            .prescription(rx)
            .patient(rx.getPatient())
            .hospital(rx.getHospital())
            .medicationName(rx.getMedicationName())
            .dose(buildDoseDisplay(rx))
            .route(rx.getRoute() != null ? rx.getRoute() : "PO")
            .scheduledTime(computeMedicationDueTime(rx, LocalDateTime.now()))
            .status(MedicationAdministrationStatus.PENDING)
            .build();
        resolveNurseStaff(nurseUserId, hospitalId).ifPresent(seeded::setAdministeredByStaff);
        return marRepository.save(seeded);
    }

    /**
     * Stamp a finalised MAR row with the override decision once the
     * administration is being recorded. Always re-runs the five-rights check
     * — the TIME right depends on the final {@code administeredAt}, which is
     * set by the administer call (not by verify), so a row that was
     * VERIFIED earlier may now be outside the time window. The route used
     * here is the persisted scanned route ({@code routeScanValue}), not the
     * prescription's own route, so a route mismatch caught at verify is not
     * silently exonerated by reusing the prescribed value.
     */
    private void recordOverrideOnAdminister(
        MedicationAdministrationRecord mar,
        MedicationAdministrationStatus status,
        String overrideReason
    ) {
        if (status != MedicationAdministrationStatus.GIVEN) return;

        FiveRightsVerificationResult check = fiveRightsVerificationService.verify(
            mar,
            mar.getPatientScanValue(),
            mar.getMedicationScanValue(),
            mar.getDoseScanValue(),
            mar.getRouteScanValue(),
            mar.getAdministeredAt()
        );
        if (check.allPassed()) {
            mar.setFiveRightsStatus(FiveRightsStatus.VERIFIED);
            mar.setFiveRightsOverrides(null);
            mar.setOverrideReason(null);
            return;
        }

        if (overrideReason == null || overrideReason.isBlank()) {
            throw new BusinessException(
                "Five-rights check failed (" + check.failedChecks() + "); an override reason is required to record GIVEN.");
        }
        mar.setFiveRightsStatus(FiveRightsStatus.OVERRIDDEN);
        mar.setOverrideReason(overrideReason);
        mar.setFiveRightsOverrides(serializeOverrides(check.failedChecks()));
    }

    private String serializeOverrides(Set<FiveRightsCheck> failed) {
        try {
            return objectMapper.writeValueAsString(failed.stream().map(Enum::name).toList());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize five-rights overrides; falling back to toString. {}", e.getMessage());
            return failed.stream().map(Enum::name).toList().toString();
        }
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
       Orders — derived from the real lab / imaging / procedure order tables
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseOrderTaskResponseDTO> getOrderTasks(UUID nurseUserId, UUID hospitalId, String statusFilter, int limit) {
        int effectiveLimit = clampLimit(limit);
        String normalized = statusFilter != null && !statusFilter.isBlank()
            ? statusFilter.trim().toUpperCase(Locale.ROOT) : null;

        return deriveOrderTasks(nurseUserId, hospitalId).stream()
            .filter(t -> normalized == null
                || (t.getPriority() != null && normalized.equals(t.getPriority().toUpperCase(Locale.ROOT))))
            .limit(effectiveLimit)
            .toList();
    }

    /**
     * Nurse-actionable orders: lab draws not yet collected, imaging studies
     * still in prep, procedures not yet under way. Three hospital-level
     * queries, then an in-memory filter to the nurse's assigned patients —
     * never per-patient queries (N+1 with the all-hospital fallback).
     */
    private List<NurseOrderTaskResponseDTO> deriveOrderTasks(UUID nurseUserId, UUID hospitalId) {
        if (hospitalId == null) return List.of();
        Set<UUID> scope = assignedPatientIds(nurseUserId, hospitalId);

        List<NurseOrderTaskResponseDTO> tasks = new ArrayList<>();
        for (LabOrder order : labOrderRepository.findByHospital_IdAndStatusIn(hospitalId, NURSE_ACTION_LAB_STATUSES)) {
            Patient patient = order.getPatient();
            if (!inScope(patient, scope)) continue;
            tasks.add(orderTask(order.getId(), patient, "Lab",
                normalizePriority(order.getPriority()), order.getOrderDatetime()));
        }
        for (ImagingOrder order : imagingOrderRepository
                .findByHospital_IdAndStatusInOrderByOrderedAtDesc(hospitalId, NURSE_ACTION_IMAGING_STATUSES)) {
            Patient patient = order.getPatient();
            if (!inScope(patient, scope)) continue;
            tasks.add(orderTask(order.getId(), patient, "Imaging",
                order.getPriority() != null ? order.getPriority().name() : PRIORITY_ROUTINE,
                order.getOrderedAt()));
        }
        for (ProcedureOrder order : procedureOrderRepository
                .findByHospital_IdAndStatusIn(hospitalId, NURSE_ACTION_PROCEDURE_STATUSES)) {
            Patient patient = order.getPatient();
            if (!inScope(patient, scope)) continue;
            tasks.add(orderTask(order.getId(), patient, "Procedure",
                order.getUrgency() != null ? order.getUrgency().name() : PRIORITY_ROUTINE,
                order.getScheduledDatetime() != null ? order.getScheduledDatetime() : order.getOrderedAt()));
        }

        tasks.sort(Comparator.comparing(NurseOrderTaskResponseDTO::getDueTime,
            Comparator.nullsLast(Comparator.naturalOrder())));
        return tasks;
    }

    private NurseOrderTaskResponseDTO orderTask(
        UUID id, Patient patient, String orderType, String priority, LocalDateTime dueTime
    ) {
        return NurseOrderTaskResponseDTO.builder()
            .id(id)
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .orderType(orderType)
            .priority(priority)
            .dueTime(dueTime)
            .build();
    }

    private boolean inScope(Patient patient, Set<UUID> scope) {
        return patient != null && (scope == null || scope.contains(patient.getId()));
    }

    private String normalizePriority(String raw) {
        return raw == null || raw.isBlank() ? PRIORITY_ROUTINE : raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Patient-ID scope for assignee=me queries. {@code null} means "no
     * filter": either the caller asked for the whole unit (assignee=all) or
     * the nurse has no explicit assignments — mirroring the all-hospital
     * fallback in {@link #resolvePatients}.
     */
    private Set<UUID> assignedPatientIds(UUID nurseUserId, UUID hospitalId) {
        if (nurseUserId == null || hospitalId == null) return null;
        List<PatientResponseDTO> assigned = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null);
        Set<UUID> ids = new HashSet<>();
        for (PatientResponseDTO p : assigned) {
            if (p.getId() != null) ids.add(p.getId());
        }
        return ids.isEmpty() ? null : ids;
    }

    /* ═══════════════════════════════════════════════════════════════════
       Handoffs — persisted SBAR records (P0 #1)
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseHandoffSummaryDTO> getHandoffSummaries(UUID nurseUserId, UUID hospitalId, int limit, String status) {
        if (hospitalId == null) return List.of();
        int effectiveLimit = clampLimit(limit);
        Set<UUID> scope = assignedPatientIds(nurseUserId, hospitalId);

        // Only the two statuses that exist; anything else is a caller bug and
        // silently returning [] for a typo would read as "no handoffs".
        String effectiveStatus = status == null || status.isBlank() ? STATUS_PENDING : status.trim().toUpperCase();
        if (!STATUS_PENDING.equals(effectiveStatus) && !STATUS_COMPLETED.equals(effectiveStatus)) {
            throw new BusinessException("Unknown handoff status: " + status);
        }

        // Scan page is larger than the display limit so the assignee filter
        // can still fill it.
        return nurseHandoffRepository
            .findByHospital_IdAndStatusOrderByCreatedAtDesc(hospitalId, effectiveStatus,
                PageRequest.of(0, HANDOFF_SCAN_PAGE))
            .stream()
            .filter(h -> inScope(h.getPatient(), scope))
            .limit(effectiveLimit)
            .map(this::toHandoffSummary)
            .toList();
    }

    @Override
    @Transactional
    public NurseHandoffSummaryDTO createHandoff(UUID nurseUserId, UUID hospitalId, NurseHandoffCreateRequestDTO request) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + request.getPatientId()));

        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        NurseHandoff handoff = NurseHandoff.builder()
            .hospital(hospital)
            .patient(patient)
            .direction(request.getDirection().trim())
            .situation(trimToNull(request.getSituation()))
            .background(trimToNull(request.getBackground()))
            .assessment(trimToNull(request.getAssessment()))
            .recommendation(trimToNull(request.getRecommendation()))
            .createdByName(resolveNurseName(nurseUserId))
            .build();

        return toHandoffSummary(nurseHandoffRepository.save(handoff));
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
        // ── Tenant isolation: id + hospital lookup — a handoff from another
        // hospital reads as not-found. ──
        NurseHandoff handoff = nurseHandoffRepository.findByIdAndHospital_Id(handoffId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Handoff not found: " + handoffId));
        if (STATUS_COMPLETED.equals(handoff.getStatus())) {
            return; // already completed — keep the endpoint idempotent
        }
        handoff.setStatus(STATUS_COMPLETED);
        handoff.setCompletedAt(LocalDateTime.now());
        handoff.setCompletedByName(resolveNurseName(nurseUserId));
        nurseHandoffRepository.save(handoff);
    }

    private NurseHandoffSummaryDTO toHandoffSummary(NurseHandoff h) {
        Patient patient = h.getPatient();
        return NurseHandoffSummaryDTO.builder()
            .id(h.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : DEFAULT_PATIENT_NAME)
            .direction(h.getDirection())
            .updatedAt(h.getUpdatedAt() != null ? h.getUpdatedAt() : h.getCreatedAt())
            .note(h.getSituation())
            .background(h.getBackground())
            .assessment(h.getAssessment())
            .recommendation(h.getRecommendation())
            .status(h.getStatus())
            .createdByName(h.getCreatedByName())
            .completedAt(h.getCompletedAt())
            .completedByName(h.getCompletedByName())
            .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /* ═══════════════════════════════════════════════════════════════════
       Announcements — backed by real Announcement entity
       ═══════════════════════════════════════════════════════════════════ */

    @Override
    public List<NurseAnnouncementDTO> getAnnouncements(UUID hospitalId, int limit) {
        if (hospitalId == null) return List.of();
        int effectiveLimit = clampLimit(limit);
        Pageable page = PageRequest.of(0, effectiveLimit);

        List<Announcement> dbAnnouncements = announcementRepository
            .findByHospital_IdOrderByDateDesc(hospitalId, page)
            .getContent();

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

        return List.of();
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

        long ordersPending = deriveOrderTasks(nurseUserId, hospitalId).size();
        long handoffsPending = countPendingHandoffs(nurseUserId, hospitalId);

        // Announcement count
        long announcementCount = hospitalId != null ? announcementRepository.countByHospital_Id(hospitalId) : 0L;

        return NurseDashboardSummaryDTO.builder()
            .assignedPatients(assignedPatients)
            .vitalsDue(vitalsDue)
            .medicationsDue(medCounts[0])
            .medicationsOverdue(medCounts[1])
            .ordersPending(ordersPending)
            .handoffsPending(handoffsPending)
            .announcements(announcementCount)
            .build();
    }

    /** Pending-handoff count matching the same assignee scope as the list endpoint. */
    private long countPendingHandoffs(UUID nurseUserId, UUID hospitalId) {
        if (hospitalId == null) return 0L;
        Set<UUID> scope = assignedPatientIds(nurseUserId, hospitalId);
        if (scope == null) {
            return nurseHandoffRepository.countByHospital_IdAndStatus(hospitalId, STATUS_PENDING);
        }
        return nurseHandoffRepository
            .findByHospital_IdAndStatusOrderByCreatedAtDesc(hospitalId, STATUS_PENDING,
                PageRequest.of(0, HANDOFF_SCAN_PAGE))
            .stream()
            .filter(h -> inScope(h.getPatient(), scope))
            .count();
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
        MedicationAdministrationStatus status, String note, String overrideReason
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
        // No verify call has happened for a fresh prescription-as-task path —
        // GIVEN must therefore be explicitly overridden by the nurse. We
        // stamp scanVerifiedAt with the override decision time so audit
        // queries that range over scan_verified_at still see this record.
        if (status == MedicationAdministrationStatus.GIVEN) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new BusinessException(
                    "Five-rights verification has not been completed; an override reason is required to record GIVEN.");
            }
            marRecord.setFiveRightsStatus(FiveRightsStatus.OVERRIDDEN);
            marRecord.setOverrideReason(overrideReason);
            marRecord.setFiveRightsOverrides(serializeOverrides(EnumSet.allOf(FiveRightsCheck.class)));
            marRecord.setScanVerifiedAt(LocalDateTime.now());
        }
        MedicationAdministrationRecord saved = marRepository.save(marRecord);

        log.info("MAR recorded: prescriptionId={}, status={}, fiveRights={}, nurse={}",
            rx.getId(), status, marRecord.getFiveRightsStatus(), nurseUserId);

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
    Private helpers — patient resolution
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
            return List.of();
        }
        return deduplicatePatientContexts(patients);
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
        if (hospitalId == null) return List.of();
        List<PatientResponseDTO> patients = nurseDashboardService.getPatientsForNurse(nurseUserId, hospitalId, null);
        if (patients.isEmpty()) {
            log.warn("No assigned patients found for nurse {}, falling back to all-hospital patient list for hospital {}",
                    nurseUserId, hospitalId);
            patients = nurseDashboardService.getPatientsForNurse(null, hospitalId, null);
        }
        return patients;
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

    private String normalizeAdministrationStatus(NurseMedicationAdministrationRequestDTO request) {
        if (request == null || request.getStatus() == null) return DEFAULT_ADMINISTRATION_STATUS;
        String normalized = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) return DEFAULT_ADMINISTRATION_STATUS;
        if (!SUPPORTED_ADMINISTRATION_STATUSES.contains(normalized)) {
            throw new BusinessException("Unsupported medication administration status: " + request.getStatus());
        }
        return normalized;
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
            NurseWorkboardPatientDTO card = toWorkboardCard(a, hospitalId, overdueThreshold, now);
            if (card != null) {
                result.add(card);
            }
        }
        return result;
    }

    private NurseWorkboardPatientDTO toWorkboardCard(Admission a, UUID hospitalId,
                                                     LocalDateTime overdueThreshold, LocalDateTime now) {
        UUID admissionId = a.getId();
        Patient patient = JpaProxyUtils.safeInit(a.getPatient(), ADMISSION_OWNER, admissionId, ASSOCIATION_PATIENT);
        if (patient == null) return null;

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

        Department department = JpaProxyUtils.safeInit(
            a.getDepartment(), ADMISSION_OWNER, admissionId, ASSOCIATION_DEPARTMENT);
        Staff admittingProvider = JpaProxyUtils.safeInit(
            a.getAdmittingProvider(), ADMISSION_OWNER, admissionId, "admittingProvider");
        String departmentName = department != null ? department.getName() : null;
        String attendingDoctor = admittingProvider != null ? admittingProvider.getFullName() : null;

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
            if (card == null) continue;
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
        UUID admissionId = a.getId();
        Patient patient = JpaProxyUtils.safeInit(a.getPatient(), ADMISSION_OWNER, admissionId, ASSOCIATION_PATIENT);
        if (patient == null) return null;

        Hospital hospital = JpaProxyUtils.safeInit(a.getHospital(), ADMISSION_OWNER, admissionId, "hospital");
        Department department = JpaProxyUtils.safeInit(
            a.getDepartment(), ADMISSION_OWNER, admissionId, ASSOCIATION_DEPARTMENT);
        long waitMinutes = a.getAdmissionDateTime() != null
            ? com.example.hms.utility.ElapsedTime.minutesBetween(a.getAdmissionDateTime(), now) : 0;
        UUID hospId = hospital != null ? hospital.getId() : null;
        return NurseFlowPatientCardDTO.builder()
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(hospId != null ? patient.getMrnForHospital(hospId) : null)
            .admissionId(a.getId())
            .acuityLevel(a.getAcuityLevel() != null ? a.getAcuityLevel().name() : null)
            .waitMinutes(waitMinutes)
            .roomBed(a.getRoomBed())
            .departmentName(department != null ? department.getName() : null)
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
            .heightCm(request.getHeightCm())
            .headCircumferenceCm(request.getHeadCircumferenceCm())
            .onOxygen(request.getOnOxygen())
            .consciousnessLevel(request.getConsciousnessLevel())
            .notes(request.getNotes())
            .build();

        // Significant when the legacy per-vital thresholds fire OR the
        // NEWS2 aggregate reaches MEDIUM (P3 #25b) — the aggregate catches
        // the multi-parameter deterioration the per-vital ranges miss.
        boolean clinicallySig = isClinicallySignificant(request)
            || com.example.hms.utility.NewsScoreCalculator.score(vital).total() >= 5;
        vital.setClinicallySignificant(clinicallySig);

        resolveNurseStaff(nurseUserId, hospitalId).ifPresent(vital::setRecordedByStaff);
        vitalSignRepository.save(vital);

        // Advance encounter status after vitals are captured.
        // ARRIVED → WAITING_FOR_PHYSICIAN  (vitals captured = triage complete)
        // TRIAGE  → WAITING_FOR_PHYSICIAN  (patient was already in triage, now done)
        encounterRepository
            .findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
                patientId, hospitalId, EncounterStatus.ARRIVED)
            .ifPresent(encounter -> {
                encounter.setTriageTimestamp(LocalDateTime.now());
                encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
                encounterRepository.save(encounter);
                log.info("Encounter {} transitioned ARRIVED → WAITING_FOR_PHYSICIAN after vitals", encounter.getId());
            });

        encounterRepository
            .findFirstByPatient_IdAndHospital_IdAndStatusOrderByEncounterDateDesc(
                patientId, hospitalId, EncounterStatus.TRIAGE)
            .ifPresent(encounter -> {
                encounter.setTriageTimestamp(LocalDateTime.now());
                encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
                encounterRepository.save(encounter);
                log.info("Encounter {} transitioned TRIAGE → WAITING_FOR_PHYSICIAN after vitals", encounter.getId());
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
            NurseAdmissionSummaryDTO summary = toAdmissionSummary(a);
            if (summary != null) {
                result.add(summary);
            }
        }
        for (Admission a : awaitingDischarge) {
            // Avoid duplicates if somehow already included
            if (result.stream().noneMatch(r -> a.getId().equals(r.getAdmissionId()))) {
                NurseAdmissionSummaryDTO summary = toAdmissionSummary(a);
                if (summary != null) {
                    result.add(summary);
                }
            }
        }
        return result;
    }

    private NurseAdmissionSummaryDTO toAdmissionSummary(Admission a) {
        UUID admissionId = a.getId();
        Patient patient = JpaProxyUtils.safeInit(a.getPatient(), ADMISSION_OWNER, admissionId, ASSOCIATION_PATIENT);
        if (patient == null) return null;

        Hospital hospital = JpaProxyUtils.safeInit(a.getHospital(), ADMISSION_OWNER, admissionId, "hospital");
        Department department = JpaProxyUtils.safeInit(
            a.getDepartment(), ADMISSION_OWNER, admissionId, ASSOCIATION_DEPARTMENT);
        Staff admittingProvider = JpaProxyUtils.safeInit(
            a.getAdmittingProvider(), ADMISSION_OWNER, admissionId, "admittingProvider");
        UUID hospId = hospital != null ? hospital.getId() : null;
        return NurseAdmissionSummaryDTO.builder()
            .admissionId(a.getId())
            .patientId(patient.getId())
            .patientName(patient.getFullName())
            .mrn(hospId != null ? patient.getMrnForHospital(hospId) : null)
            .status(a.getStatus() != null ? a.getStatus().name() : null)
            .acuityLevel(a.getAcuityLevel() != null ? a.getAcuityLevel().name() : null)
            .roomBed(a.getRoomBed())
            .departmentName(department != null ? department.getName() : null)
            .admittingDoctor(admittingProvider != null ? admittingProvider.getFullName() : null)
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
            .priority(request.getPriority() != null
                ? request.getPriority().toUpperCase(Locale.ROOT) : PRIORITY_ROUTINE)
            .status(STATUS_PENDING)
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
            && STATUS_PENDING.equals(t.getStatus())
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
