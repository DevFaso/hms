package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
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
import com.example.hms.payload.dto.nurse.FlowsheetEntryCreateRequestDTO;
import com.example.hms.payload.dto.nurse.FlowsheetEntryResponseDTO;
import com.example.hms.payload.dto.nurse.BcmaComplianceDTO;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.NurseTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/nurse")
@RequiredArgsConstructor
@Tag(name = "Nurse Workflow", description = "Operational queues powering the nurse dashboard")
public class NurseTaskController {

    private static final String NURSE_IDENTITY_ERROR = "Unable to resolve nurse identity.";

    private final NurseTaskService nurseTaskService;
    private final NurseDashboardService nurseDashboardService;
    private final ControllerAuthUtils authUtils;

    @GetMapping("/patients")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Assigned patient list — active in-house patients for the nurse")
    public ResponseEntity<List<PatientResponseDTO>> getAssignedPatients(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseDashboardService.getPatientsForNurse(nurseId, scopedHospital, null));
    }

    @GetMapping("/vitals/due")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load due vital sign tasks for the authenticated nurse")
    public ResponseEntity<List<NurseVitalTaskResponseDTO>> getDueVitals(
        @RequestParam(name = "window", required = false) String window,
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        Duration duration = parseWindow(window);
        return ResponseEntity.ok(nurseTaskService.getDueVitals(nurseId, scopedHospital, duration));
    }

    @GetMapping("/medications/mar")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load medication administration tasks for the authenticated nurse")
    public ResponseEntity<List<NurseMedicationTaskResponseDTO>> getMedicationTasks(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getMedicationTasks(nurseId, scopedHospital, status));
    }

    @PutMapping("/medications/mar/{taskId}/administer")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Record medication administration outcome for a MAR task")
    public ResponseEntity<NurseMedicationTaskResponseDTO> administerMedication(
        @PathVariable("taskId") UUID taskId,
        @Valid @RequestBody NurseMedicationAdministrationRequestDTO request,
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        UUID nurseId = resolveAssignee(auth, assignee);
        if (nurseId == null) {
            nurseId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve nurse assignment for administration."));
        }
        NurseMedicationTaskResponseDTO response = nurseTaskService.recordMedicationAdministration(taskId, nurseId, scopedHospital, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load pending clinical orders for the nurse queue")
    public ResponseEntity<List<NurseOrderTaskResponseDTO>> getOrders(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        int effectiveLimit = safeLimit(limit, 6, 20);
        return ResponseEntity.ok(nurseTaskService.getOrderTasks(nurseId, scopedHospital, status, effectiveLimit));
    }

    @GetMapping("/handoffs")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load upcoming patient handoffs for the authenticated nurse")
    public ResponseEntity<List<NurseHandoffSummaryDTO>> getHandoffs(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        int effectiveLimit = safeLimit(limit, 6, 20);
        return ResponseEntity.ok(nurseTaskService.getHandoffSummaries(nurseId, scopedHospital, effectiveLimit));
    }

    @PostMapping("/handoffs")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a new nurse-to-nurse handoff report for a patient")
    public ResponseEntity<NurseHandoffSummaryDTO> createHandoff(
        @Valid @RequestBody NurseHandoffCreateRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, null);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        NurseHandoffSummaryDTO created = nurseTaskService.createHandoff(nurseId, scopedHospital, request);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(created);
    }

    @PutMapping("/handoffs/{handoffId}/complete")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark a patient transfer or handoff as completed")
    public ResponseEntity<Void> completeHandoff(
        @PathVariable UUID handoffId,
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        try {
            nurseTaskService.completeHandoff(handoffId, nurseId, scopedHospital);
        } catch (ResourceNotFoundException ignored) {
            // Treat missing handoffs as already completed to keep the operation idempotent.
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/handoffs/{handoffId}/tasks/{taskId}")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update completion status for a handoff checklist item")
    public ResponseEntity<NurseHandoffChecklistUpdateResponseDTO> updateHandoffChecklist(
        @PathVariable UUID handoffId,
        @PathVariable UUID taskId,
        @Valid @RequestBody NurseHandoffChecklistUpdateRequestDTO request,
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        boolean completed = Boolean.TRUE.equals(request.getCompleted());
        NurseHandoffChecklistUpdateResponseDTO response = nurseTaskService.updateHandoffChecklistItem(
            handoffId,
            taskId,
            nurseId,
            scopedHospital,
            completed
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/announcements")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Load shift announcements scoped to the nurse's hospital")
    public ResponseEntity<List<NurseAnnouncementDTO>> getAnnouncements(
        @RequestParam(name = "limit", required = false) Integer limit,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        authUtils.requireAuth(auth);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        int effectiveLimit = safeLimit(limit, 5, 20);
        return ResponseEntity.ok(nurseTaskService.getAnnouncements(scopedHospital, effectiveLimit));
    }

    @GetMapping("/dashboard/summary")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Aggregated summary counts powering the nurse dashboard header cards")
    public ResponseEntity<NurseDashboardSummaryDTO> getDashboardSummary(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getDashboardSummary(nurseId, scopedHospital));
    }

    // ── MVP 12 endpoints ──────────────────────────────────────────────

    @GetMapping("/workboard")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Patient workboard — active admissions list for the nurse unit")
    public ResponseEntity<java.util.List<NurseWorkboardPatientDTO>> getWorkboard(
        @RequestParam(name = "assignee", required = false) String assignee,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = resolveAssignee(auth, assignee);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getWorkboard(nurseId, scopedHospital));
    }

    @GetMapping("/patient-flow")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Patient flow board — Kanban lanes by admission status/acuity")
    public ResponseEntity<NurseFlowBoardDTO> getPatientFlow(
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getPatientFlow(scopedHospital, departmentId));
    }

    @PostMapping("/patients/{patientId}/vitals")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Inline vital sign capture from the nurse station")
    public ResponseEntity<Void> captureVitals(
        @PathVariable UUID patientId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        @Valid @RequestBody NurseVitalCaptureRequestDTO request,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseUserId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(NURSE_IDENTITY_ERROR));
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        nurseTaskService.captureVitals(patientId, nurseUserId, scopedHospital, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admissions/pending")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Pending admissions and discharge-ready patients")
    public ResponseEntity<java.util.List<NurseAdmissionSummaryDTO>> getPendingAdmissions(
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getPendingAdmissions(scopedHospital, departmentId));
    }

    // ── MVP 13 endpoints ──────────────────────────────────────────────

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Nursing task board — bedside care tasks for the unit")
    public ResponseEntity<java.util.List<NurseTaskItemDTO>> getNursingTaskBoard(
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getNursingTaskBoard(scopedHospital, status));
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a new bedside nursing task")
    public ResponseEntity<NurseTaskItemDTO> createNursingTask(
        @Valid @RequestBody NurseTaskCreateRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(NURSE_IDENTITY_ERROR));
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        NurseTaskItemDTO created = nurseTaskService.createNursingTask(nurseId, scopedHospital, request);
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/tasks/{taskId}/complete")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark a nursing task as completed")
    public ResponseEntity<NurseTaskItemDTO> completeNursingTask(
        @PathVariable UUID taskId,
        @RequestBody(required = false) NurseTaskCompleteRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(NURSE_IDENTITY_ERROR));
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.completeNursingTask(taskId, nurseId, scopedHospital, request));
    }

    @GetMapping("/inbox")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Nurse communication inbox — recent clinical notifications")
    public ResponseEntity<java.util.List<NurseInboxItemDTO>> getNurseInbox(
        @RequestParam(name = "limit", required = false) Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        String username = auth.getName();
        int effectiveLimit = safeLimit(limit, 20, 50);
        return ResponseEntity.ok(nurseTaskService.getNurseInboxItems(username, effectiveLimit));
    }

    @PatchMapping("/inbox/{itemId}/read")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark a nurse inbox notification as read")
    public ResponseEntity<Void> markInboxRead(
        @PathVariable UUID itemId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        String username = auth.getName();
        nurseTaskService.markNurseInboxRead(itemId, username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/patients/{patientId}/care-note")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a quick bedside care note (DAR or SOAPIE) from the nurse dashboard")
    public ResponseEntity<NurseCareNoteResponseDTO> createCareNote(
        @PathVariable UUID patientId,
        @Valid @RequestBody NurseCareNoteRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID nurseId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException(NURSE_IDENTITY_ERROR));
        UUID scopedHospital = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.status(201).body(
            nurseTaskService.createCareNote(patientId, nurseId, scopedHospital, request));
    }

    private UUID ensureHospitalScope(Authentication auth, UUID requestedHospital) {
        UUID resolved = authUtils.resolveHospitalScope(auth, requestedHospital, false);
        if (resolved == null && !authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            throw new BusinessException("Hospital context required for nurse workflow data.");
        }
        return resolved;
    }

    // ── MVP3: Task Engine + Flowsheets + BCMA ──────────────────────────

    @PutMapping("/tasks/{taskId}/reassign")
    @Operation(summary = "Reassign a nursing task to another staff member")
    public ResponseEntity<NurseTaskItemDTO> reassignTask(
            @PathVariable UUID taskId,
            @RequestParam UUID targetStaffId,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID nurseId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve nurse identity."));
        UUID hospId = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.reassignTask(taskId, targetStaffId, nurseId, hospId));
    }

    @PostMapping("/tasks/escalate")
    @Operation(summary = "Escalate all overdue tasks for a hospital")
    public ResponseEntity<Map<String, Integer>> escalateOverdueTasks(
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID hospId = ensureHospitalScope(auth, hospitalId);
        int count = nurseTaskService.escalateOverdueTasks(hospId);
        return ResponseEntity.ok(Map.of("escalatedCount", count));
    }

    @GetMapping("/patients/{patientId}/flowsheets")
    @Operation(summary = "Get flowsheet entries for a patient")
    public ResponseEntity<List<FlowsheetEntryResponseDTO>> getFlowsheetEntries(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID hospId = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getFlowsheetEntries(patientId, hospId, type));
    }

    @PostMapping("/flowsheets")
    @Operation(summary = "Record a flowsheet entry")
    public ResponseEntity<FlowsheetEntryResponseDTO> recordFlowsheetEntry(
            @Valid @RequestBody FlowsheetEntryCreateRequestDTO request,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID nurseId = authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("Unable to resolve nurse identity."));
        UUID hospId = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(nurseTaskService.recordFlowsheetEntry(nurseId, hospId, request));
    }

    @GetMapping("/bcma/compliance")
    @Operation(summary = "Get BCMA compliance statistics")
    public ResponseEntity<BcmaComplianceDTO> getBcmaCompliance(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) UUID hospitalId,
            Authentication auth) {

        UUID hospId = ensureHospitalScope(auth, hospitalId);
        return ResponseEntity.ok(nurseTaskService.getBcmaCompliance(hospId, hours));
    }

    /* ── Helpers ──────────────────────────────────────────────────────── */

    private UUID resolveAssignee(Authentication auth, String assignee) {
        if (assignee == null || assignee.isBlank() || "all".equalsIgnoreCase(assignee)) {
            return null;
        }
        if ("me".equalsIgnoreCase(assignee.trim())) {
            return authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve user identity for 'me' filter."));
        }
        return null;
    }

    private Duration parseWindow(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ofHours(2);
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("h")) {
                long hours = Long.parseLong(normalized.substring(0, normalized.length() - 1));
                long clamped = clampLong(hours, 1, 12);
                return Duration.ofHours(clamped);
            }
            if (normalized.endsWith("m")) {
                long minutes = Long.parseLong(normalized.substring(0, normalized.length() - 1));
                long clamped = clampLong(minutes, 15, 720);
                return Duration.ofMinutes(clamped);
            }
            long minutes = Long.parseLong(normalized);
            long clamped = clampLong(minutes, 15, 720);
            return Duration.ofMinutes(clamped);
        } catch (NumberFormatException ignored) {
            return Duration.ofHours(2);
        }
    }

    private int safeLimit(Integer limit, int defaultValue, int max) {
        if (limit == null) {
            return defaultValue;
        }
        return clampInt(limit, 1, max);
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
