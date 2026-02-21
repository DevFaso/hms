package com.example.hms.controller;

import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import com.example.hms.service.MaternalHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/maternal-history")
@RequiredArgsConstructor
@Validated
@Tag(name = "Maternal History", description = "Comprehensive maternal and reproductive health documentation")
public class MaternalHistoryController {

    private final MaternalHistoryService maternalHistoryService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a new maternal history record",
               description = "Creates a comprehensive maternal history record for a patient with version 1")
    public ResponseEntity<MaternalHistoryResponseDTO> createMaternalHistory(
        @Valid @RequestBody MaternalHistoryRequestDTO request,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.createMaternalHistory(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MANAGE_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update a maternal history record",
               description = "Creates a new version of the maternal history record")
    public ResponseEntity<MaternalHistoryResponseDTO> updateMaternalHistory(
        @PathVariable UUID id,
        @Valid @RequestBody MaternalHistoryRequestDTO request,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.updateMaternalHistory(id, request, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get maternal history by ID",
               description = "Retrieves a specific maternal history record by its ID")
    public ResponseEntity<MaternalHistoryResponseDTO> getMaternalHistoryById(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.getMaternalHistoryById(id, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/current")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get current maternal history for patient",
               description = "Retrieves the latest version of maternal history for a patient")
    public ResponseEntity<MaternalHistoryResponseDTO> getCurrentMaternalHistory(
        @PathVariable UUID patientId,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.getCurrentMaternalHistoryByPatientId(patientId, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/versions")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get all versions of maternal history",
               description = "Retrieves all historical versions of maternal history for a patient")
    public ResponseEntity<List<MaternalHistoryResponseDTO>> getAllVersionsByPatient(
        @PathVariable UUID patientId,
        Authentication authentication
    ) {
        String username = authentication.getName();
        List<MaternalHistoryResponseDTO> response = maternalHistoryService.getAllVersionsByPatientId(patientId, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/version/{versionNumber}")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get specific version of maternal history",
               description = "Retrieves a specific version of maternal history for a patient")
    public ResponseEntity<MaternalHistoryResponseDTO> getMaternalHistoryVersion(
        @PathVariable UUID patientId,
        @PathVariable Integer versionNumber,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.getMaternalHistoryByPatientIdAndVersion(patientId, versionNumber, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital/{hospitalId}/search")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Search maternal history records",
               description = "Search maternal history records with multiple criteria")
    public ResponseEntity<Page<MaternalHistoryResponseDTO>> searchMaternalHistory(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) String riskCategory,
        @RequestParam(required = false) Boolean dataComplete,
        @RequestParam(required = false) Boolean reviewedByProvider,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication authentication
    ) {
        String username = authentication.getName();
        Page<MaternalHistoryResponseDTO> response = maternalHistoryService.searchMaternalHistory(
            hospitalId, patientId, riskCategory, dataComplete, reviewedByProvider,
            dateFrom, dateTo, pageable, username
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital/{hospitalId}/high-risk")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get high-risk maternal histories",
               description = "Retrieves all high-risk maternal history records for a hospital")
    public ResponseEntity<Page<MaternalHistoryResponseDTO>> getHighRiskMaternities(
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication authentication
    ) {
        String username = authentication.getName();
        Page<MaternalHistoryResponseDTO> response = maternalHistoryService.getHighRiskMaternalHistory(hospitalId, pageable, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital/{hospitalId}/pending-review")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get maternal histories pending review",
               description = "Retrieves maternal histories created in the last 48 hours pending review")
    public ResponseEntity<Page<MaternalHistoryResponseDTO>> getPendingReview(
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication authentication
    ) {
        String username = authentication.getName();
        Page<MaternalHistoryResponseDTO> response = maternalHistoryService.getPendingReview(hospitalId, pageable, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital/{hospitalId}/specialist-referral")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get cases requiring specialist referral",
               description = "Retrieves maternal histories requiring specialist referral")
    public ResponseEntity<Page<MaternalHistoryResponseDTO>> getRequiringSpecialistReferral(
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication authentication
    ) {
        String username = authentication.getName();
        Page<MaternalHistoryResponseDTO> response = maternalHistoryService.getRequiringSpecialistReferral(hospitalId, pageable, username);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital/{hospitalId}/psychosocial-concerns")
    @PreAuthorize("hasAnyAuthority('VIEW_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get cases with psychosocial concerns",
               description = "Retrieves maternal histories with psychosocial risk factors")
    public ResponseEntity<Page<MaternalHistoryResponseDTO>> getWithPsychosocialConcerns(
        @PathVariable UUID hospitalId,
        @PageableDefault(size = 20) Pageable pageable,
        Authentication authentication
    ) {
        String username = authentication.getName();
        Page<MaternalHistoryResponseDTO> response = maternalHistoryService.getWithPsychosocialConcerns(hospitalId, pageable, username);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MANAGE_MATERNAL_HISTORY', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete a maternal history record",
               description = "Soft deletes a maternal history record (marks as inactive)")
    public ResponseEntity<Void> deleteMaternalHistory(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String username = authentication.getName();
        maternalHistoryService.deleteMaternalHistory(id, username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/mark-reviewed")
    @PreAuthorize("hasAnyAuthority('MANAGE_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark maternal history as reviewed",
               description = "Marks a maternal history record as reviewed by a provider")
    public ResponseEntity<MaternalHistoryResponseDTO> markAsReviewed(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.markAsReviewed(id, username);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/calculate-risk")
    @PreAuthorize("hasAnyAuthority('MANAGE_MATERNAL_HISTORY', 'ROLE_DOCTOR', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Calculate risk score",
               description = "Manually calculates/recalculates the risk score for a maternal history record")
    public ResponseEntity<MaternalHistoryResponseDTO> calculateRiskScore(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String username = authentication.getName();
        MaternalHistoryResponseDTO response = maternalHistoryService.calculateRiskScore(id, username);
        return ResponseEntity.ok(response);
    }
}
