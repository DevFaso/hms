package com.example.hms.controller;

import com.example.hms.payload.dto.*;
import com.example.hms.service.AdmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller for admission management
 */
@RestController
@RequestMapping("/api/admissions")
@RequiredArgsConstructor
@Tag(name = "Admission Management", description = "Endpoints for managing patient admissions and order sets")
public class AdmissionController {

    private final AdmissionService admissionService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Admit a patient", description = "Create a new hospital admission for a patient")
    public ResponseEntity<AdmissionResponseDTO> admitPatient(@Valid @RequestBody AdmissionRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admissionService.admitPatient(request));
    }

    @GetMapping("/{admissionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Get admission details", description = "Retrieve admission by ID")
    public ResponseEntity<AdmissionResponseDTO> getAdmission(@PathVariable UUID admissionId) {
        return ResponseEntity.ok(admissionService.getAdmission(admissionId));
    }

    @PutMapping("/{admissionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Update admission", description = "Update admission details (room, acuity, notes, etc.)")
    public ResponseEntity<AdmissionResponseDTO> updateAdmission(
        @PathVariable UUID admissionId,
        @Valid @RequestBody AdmissionUpdateRequestDTO request
    ) {
        return ResponseEntity.ok(admissionService.updateAdmission(admissionId, request));
    }

    @PostMapping("/{admissionId}/apply-order-sets")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE')")
    @Operation(summary = "Apply order sets", description = "Apply admission order sets to an admission")
    public ResponseEntity<AdmissionResponseDTO> applyOrderSets(
        @PathVariable UUID admissionId,
        @Valid @RequestBody AdmissionOrderExecutionRequestDTO request
    ) {
        return ResponseEntity.ok(admissionService.applyOrderSets(admissionId, request));
    }

    @PostMapping("/{admissionId}/discharge")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Discharge patient", description = "Discharge a patient from hospital")
    public ResponseEntity<AdmissionResponseDTO> dischargePatient(
        @PathVariable UUID admissionId,
        @Valid @RequestBody AdmissionDischargeRequestDTO request
    ) {
        return ResponseEntity.ok(admissionService.dischargePatient(admissionId, request));
    }

    @DeleteMapping("/{admissionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Cancel admission", description = "Cancel a pending or active admission")
    public ResponseEntity<Void> cancelAdmission(@PathVariable UUID admissionId) {
        admissionService.cancelAdmission(admissionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Get admissions by patient", description = "Retrieve all admissions for a patient")
    public ResponseEntity<List<AdmissionResponseDTO>> getAdmissionsByPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(admissionService.getAdmissionsByPatient(patientId));
    }

    @GetMapping("/patient/{patientId}/current")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_HOSPITAL_ADMIN', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Get current admission for patient", description = "Retrieve active admission for a patient if any")
    public ResponseEntity<AdmissionResponseDTO> getCurrentAdmissionForPatient(@PathVariable UUID patientId) {
        AdmissionResponseDTO admission = admissionService.getCurrentAdmissionForPatient(patientId);
        if (admission == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(admission);
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get admissions by hospital", description = "Retrieve admissions for a hospital with optional filters")
    public ResponseEntity<List<AdmissionResponseDTO>> getAdmissionsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        return ResponseEntity.ok(admissionService.getAdmissionsByHospital(hospitalId, status, startDate, endDate));
    }

    // Order Set Management Endpoints

    @PostMapping("/order-sets")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create order set template", description = "Create a new admission order set template")
    public ResponseEntity<AdmissionOrderSetResponseDTO> createOrderSet(@Valid @RequestBody AdmissionOrderSetRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admissionService.createOrderSet(request));
    }

    @GetMapping("/order-sets/{orderSetId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get order set", description = "Retrieve order set template by ID")
    public ResponseEntity<AdmissionOrderSetResponseDTO> getOrderSet(@PathVariable UUID orderSetId) {
        return ResponseEntity.ok(admissionService.getOrderSet(orderSetId));
    }

    @GetMapping("/order-sets/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get order sets by hospital", description = "Retrieve order set templates for a hospital")
    public ResponseEntity<List<AdmissionOrderSetResponseDTO>> getOrderSetsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) String admissionType
    ) {
        return ResponseEntity.ok(admissionService.getOrderSetsByHospital(hospitalId, admissionType));
    }

    @PostMapping("/order-sets/{orderSetId}/deactivate")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Deactivate order set", description = "Deactivate an order set template")
    public ResponseEntity<Void> deactivateOrderSet(
        @PathVariable UUID orderSetId,
        @RequestParam String reason,
        @RequestParam UUID deactivatedByStaffId
    ) {
        admissionService.deactivateOrderSet(orderSetId, reason, deactivatedByStaffId);
        return ResponseEntity.noContent().build();
    }
}
