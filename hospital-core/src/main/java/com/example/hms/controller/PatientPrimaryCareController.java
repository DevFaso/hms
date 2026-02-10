package com.example.hms.controller;

import com.example.hms.payload.dto.*;
import com.example.hms.service.PatientPrimaryCareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping()
@RequiredArgsConstructor
@Tag(name = "Primary Care Provider (PCP)", description = "Manage patient primary care links")
public class PatientPrimaryCareController {

    private final PatientPrimaryCareService service;

    @Operation(summary = "Assign a PCP to a patient")
    @PostMapping("/patients/{patientId}/primary-care")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<PatientPrimaryCareResponseDTO> assign(
        @PathVariable UUID patientId,
        @Valid @RequestBody PatientPrimaryCareRequestDTO request) {
        return ResponseEntity.ok(service.assignPrimaryCare(patientId, request));
    }

    @Operation(summary = "Get current PCP for a patient")
    @GetMapping("/patients/{patientId}/primary-care/current")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','RECEPTIONIST','DOCTOR','NURSE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<Optional<PatientPrimaryCareResponseDTO>> getCurrent(@PathVariable UUID patientId) {
        return ResponseEntity.ok(service.getCurrentPrimaryCare(patientId));
    }

    @Operation(summary = "Get PCP history for a patient")
    @GetMapping("/patients/{patientId}/primary-care/history")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN','RECEPTIONIST','DOCTOR','NURSE','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<PatientPrimaryCareResponseDTO>> history(@PathVariable UUID patientId) {
        return ResponseEntity.ok(service.getPrimaryCareHistory(patientId));
    }

    @Operation(summary = "Update a PCP link")
    @PutMapping("/primary-care/{pcpId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN')")
    public ResponseEntity<PatientPrimaryCareResponseDTO> update(
        @PathVariable UUID pcpId,
        @Valid @RequestBody PatientPrimaryCareRequestDTO request) {
        return ResponseEntity.ok(service.updatePrimaryCare(pcpId, request));
    }

    @Operation(summary = "End a PCP link (set endDate, mark not current)")
    @PatchMapping("/primary-care/{pcpId}/end")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN')")
    public ResponseEntity<PatientPrimaryCareResponseDTO> end(
        @PathVariable UUID pcpId,
        @RequestParam(required = false) LocalDate endDate) {
        return ResponseEntity.ok(service.endPrimaryCare(pcpId, endDate));
    }

    @Operation(summary = "Delete a PCP link")
    @DeleteMapping("/primary-care/{pcpId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','HOSPITAL_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID pcpId) {
        service.deletePrimaryCare(pcpId);
        return ResponseEntity.noContent().build();
    }
}
