package com.example.hms.controller;

import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.service.DischargeSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * REST Controller for Discharge Summary operations
 * Part of Story #14: Discharge Summary Assembly
 */
@RestController
@RequestMapping("/discharge-summaries")
@RequiredArgsConstructor
@Tag(name = "Discharge Summaries", description = "Comprehensive discharge summary with medication reconciliation and pending test tracking")
public class DischargeSummaryController {

    private final DischargeSummaryService dischargeSummaryService;

    @Operation(summary = "Create a discharge summary")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<DischargeSummaryResponseDTO> createDischargeSummary(
        @Valid @RequestBody DischargeSummaryRequestDTO request,
        Locale locale
    ) {
        DischargeSummaryResponseDTO response = dischargeSummaryService.createDischargeSummary(request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Update a discharge summary (only if not finalized)")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<DischargeSummaryResponseDTO> updateDischargeSummary(
        @PathVariable UUID id,
        @Valid @RequestBody DischargeSummaryRequestDTO request,
        Locale locale
    ) {
        DischargeSummaryResponseDTO response = dischargeSummaryService.updateDischargeSummary(id, request, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Finalize a discharge summary (lock it with provider signature)")
    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<DischargeSummaryResponseDTO> finalizeDischargeSummary(
        @PathVariable UUID id,
        @RequestParam String providerSignature,
        @RequestParam UUID providerId,
        Locale locale
    ) {
        DischargeSummaryResponseDTO response = dischargeSummaryService.finalizeDischargeSummary(id, providerSignature, providerId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get discharge summary by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_SCIENTIST','ROLE_RECEPTIONIST')")
    public ResponseEntity<DischargeSummaryResponseDTO> getDischargeSummaryById(
        @PathVariable UUID id,
        Locale locale
    ) {
        DischargeSummaryResponseDTO response = dischargeSummaryService.getDischargeSummaryById(id, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get discharge summary by encounter ID")
    @GetMapping("/encounter/{encounterId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_SCIENTIST')")
    public ResponseEntity<DischargeSummaryResponseDTO> getDischargeSummaryByEncounter(
        @PathVariable UUID encounterId,
        Locale locale
    ) {
        DischargeSummaryResponseDTO response = dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get all discharge summaries for a patient")
    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_SCIENTIST')")
    public ResponseEntity<List<DischargeSummaryResponseDTO>> getDischargeSummariesByPatient(
        @PathVariable UUID patientId,
        Locale locale
    ) {
        List<DischargeSummaryResponseDTO> response = dischargeSummaryService.getDischargeSummariesByPatient(patientId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get discharge summaries for hospital within date range")
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<List<DischargeSummaryResponseDTO>> getDischargeSummariesByHospitalAndDateRange(
        @PathVariable UUID hospitalId,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
        @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
        Locale locale
    ) {
        List<DischargeSummaryResponseDTO> response = dischargeSummaryService.getDischargeSummariesByHospitalAndDateRange(hospitalId, startDate, endDate, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get unfinalized discharge summaries for hospital")
    @GetMapping("/hospital/{hospitalId}/unfinalized")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<List<DischargeSummaryResponseDTO>> getUnfinalizedDischargeSummaries(
        @PathVariable UUID hospitalId,
        Locale locale
    ) {
        List<DischargeSummaryResponseDTO> response = dischargeSummaryService.getUnfinalizedDischargeSummaries(hospitalId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get discharge summaries with pending test results")
    @GetMapping("/hospital/{hospitalId}/pending-results")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<List<DischargeSummaryResponseDTO>> getDischargeSummariesWithPendingResults(
        @PathVariable UUID hospitalId,
        Locale locale
    ) {
        List<DischargeSummaryResponseDTO> response = dischargeSummaryService.getDischargeSummariesWithPendingResults(hospitalId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get discharge summaries by provider")
    @GetMapping("/provider/{providerId}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<List<DischargeSummaryResponseDTO>> getDischargeSummariesByProvider(
        @PathVariable UUID providerId,
        Locale locale
    ) {
        List<DischargeSummaryResponseDTO> response = dischargeSummaryService.getDischargeSummariesByProvider(providerId, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Delete a discharge summary (only if not finalized)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR')")
    public ResponseEntity<Void> deleteDischargeSummary(
        @PathVariable UUID id,
        @RequestParam UUID deletedByProviderId
    ) {
        dischargeSummaryService.deleteDischargeSummary(id, deletedByProviderId);
        return ResponseEntity.noContent().build();
    }
}
