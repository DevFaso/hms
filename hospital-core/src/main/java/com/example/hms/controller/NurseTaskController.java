package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.service.NurseTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/nurse")
@RequiredArgsConstructor
@Tag(name = "Nurse Workflow", description = "Operational queues powering the nurse dashboard")
public class NurseTaskController {

    private final NurseTaskService nurseTaskService;
    private final ControllerAuthUtils authUtils;

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

    private UUID ensureHospitalScope(Authentication auth, UUID requestedHospital) {
        UUID resolved = authUtils.resolveHospitalScope(auth, requestedHospital, false);
        if (resolved == null && !authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            throw new BusinessException("Hospital context required for nurse workflow data.");
        }
        return resolved;
    }

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
