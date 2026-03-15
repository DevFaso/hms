package com.example.hms.controller;

import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.FrontDeskPatientSnapshotDTO;
import com.example.hms.payload.dto.InsuranceIssueDTO;
import com.example.hms.payload.dto.ReceptionDashboardSummaryDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ReceptionService;
import com.example.hms.utility.RoleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/reception")
@Tag(name = "Reception / Front Desk", description = "Receptionist cockpit — queue, snapshot, clearance APIs")
@RequiredArgsConstructor
public class ReceptionController {

    private final ReceptionService receptionService;
    private final RoleValidator roleValidator;

    // ── MVP 9: Summary Strip ─────────────────────────────────────────────────

    @GetMapping("/dashboard/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST')")
    @Operation(summary = "Today's front-desk summary counts")
    public ResponseEntity<ReceptionDashboardSummaryDTO> getDashboardSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID hospitalId = resolveHospitalId();
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(receptionService.getDashboardSummary(effectiveDate, hospitalId));
    }

    // ── MVP 9: Queue ─────────────────────────────────────────────────────────

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST')")
    @Operation(summary = "Today's appointment queue with computed front-desk status")
    public ResponseEntity<List<ReceptionQueueItemDTO>> getQueue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID providerId) {
        UUID hospitalId = resolveHospitalId();
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(receptionService.getQueue(effectiveDate, hospitalId, status, departmentId, providerId));
    }

    // ── MVP 9: Patient Snapshot ───────────────────────────────────────────────

    @GetMapping("/patients/{patientId}/snapshot")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST')")
    @Operation(summary = "Front-desk patient snapshot: demographics, insurance, billing alerts")
    public ResponseEntity<FrontDeskPatientSnapshotDTO> getPatientSnapshot(@PathVariable UUID patientId) {
        UUID hospitalId = resolveHospitalId();
        return ResponseEntity.ok(receptionService.getPatientSnapshot(patientId, hospitalId));
    }

    // ── MVP 10: Insurance Issues ──────────────────────────────────────────────

    @GetMapping("/insurance/issues")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST')")
    @Operation(summary = "Patients with appointment today who have missing/expired/no-primary insurance")
    public ResponseEntity<List<InsuranceIssueDTO>> getInsuranceIssues(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID hospitalId = resolveHospitalId();
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(receptionService.getInsuranceIssues(effectiveDate, hospitalId));
    }

    // ── MVP 10: Payments Pending ──────────────────────────────────────────────

    @GetMapping("/payments/pending")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST','BILLING_SPECIALIST','ACCOUNTANT')")
    @Operation(summary = "Patients with appointment today who have outstanding invoice balances")
    public ResponseEntity<List<ReceptionQueueItemDTO>> getPaymentsPending(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID hospitalId = resolveHospitalId();
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(receptionService.getPaymentsPending(effectiveDate, hospitalId));
    }

    // ── MVP 10: Flow Board ────────────────────────────────────────────────────

    @GetMapping("/flow-board")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','RECEPTIONIST')")
    @Operation(summary = "Kanban-style patient flow board grouped by front-desk status")
    public ResponseEntity<FlowBoardDTO> getFlowBoard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID departmentId) {
        UUID hospitalId = resolveHospitalId();
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(receptionService.getFlowBoard(effectiveDate, hospitalId, departmentId));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID resolveHospitalId() {
        return HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElse(null);
    }
}
