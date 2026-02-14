package com.example.hms.controller;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.payload.dto.discharge.DischargeApprovalDecisionDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import com.example.hms.service.DischargeApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/discharge-approvals")
@RequiredArgsConstructor
@Tag(name = "Discharge Approvals", description = "Manage nurse-initiated discharge requests and doctor approvals.")
public class DischargeApprovalController {

    private final DischargeApprovalService service;

    @Operation(summary = "Create a discharge approval request (nurse)")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<DischargeApprovalResponseDTO> requestDischarge(@Valid @RequestBody DischargeApprovalRequestDTO request) {
        return ResponseEntity.ok(service.requestDischarge(request));
    }

    @Operation(summary = "Approve a discharge request (doctor)")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<DischargeApprovalResponseDTO> approve(
        @PathVariable UUID id,
        @Valid @RequestBody DischargeApprovalDecisionDTO decision
    ) {
        return ResponseEntity.ok(service.approve(id, decision));
    }

    @Operation(summary = "Reject a discharge request (doctor)")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<DischargeApprovalResponseDTO> reject(
        @PathVariable UUID id,
        @Valid @RequestBody DischargeApprovalDecisionDTO decision
    ) {
        return ResponseEntity.ok(service.reject(id, decision));
    }

    @Operation(summary = "Cancel a pending discharge request (nurse)")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<DischargeApprovalResponseDTO> cancel(
        @PathVariable UUID id,
        @RequestParam UUID staffId,
        @RequestParam(required = false) String reason
    ) {
        return ResponseEntity.ok(service.cancel(id, staffId, reason));
    }

    @Operation(summary = "Get discharge approval by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<DischargeApprovalResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @Operation(summary = "List active discharge approvals for a patient")
    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<List<DischargeApprovalResponseDTO>> getActiveForPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(service.getActiveForPatient(patientId));
    }

    @Operation(summary = "List pending discharge approvals for a hospital")
    @GetMapping("/hospital/{hospitalId}/pending")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<List<DischargeApprovalResponseDTO>> getPendingForHospital(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(service.getPendingForHospital(hospitalId));
    }

    @Operation(summary = "List discharge approvals for a hospital by status")
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<List<DischargeApprovalResponseDTO>> getByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) DischargeStatus status
    ) {
        return ResponseEntity.ok(service.getByHospitalAndStatus(hospitalId, status));
    }
}
