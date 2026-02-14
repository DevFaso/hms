package com.example.hms.controller;

import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.service.LabResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab-results")
@Tag(name = "Lab Result Management", description = "APIs for managing lab results")
@RequiredArgsConstructor
public class LabResultController {

    private final LabResultService labResultService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Create Lab Result", description = "Creates a new lab result.")
    public ResponseEntity<LabResultResponseDTO> createLabResult(
            @Valid @RequestBody LabResultRequestDTO requestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        LabResultResponseDTO created = labResultService.createLabResult(requestDTO, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Get Lab Result by ID", description = "Fetches a lab result by its ID.")
    public ResponseEntity<LabResultResponseDTO> getLabResultById(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.getLabResultById(id, locale));
    }

    @GetMapping
    // Doctors and nurses consume this list from their dashboards
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Get All Lab Results", description = "Retrieves all lab results.")
    public ResponseEntity<List<LabResultResponseDTO>> getAllLabResults(
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.getAllLabResults(locale));
    }

    @GetMapping("/pending-review")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Get Lab Results Pending Review", description = "Retrieves a curated list of lab results awaiting clinician review.")
    public ResponseEntity<List<LabResultResponseDTO>> getPendingReview(
            @RequestParam(name = "providerId", required = false) UUID providerId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.getPendingReviewResults(providerId, locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Update Lab Result", description = "Updates an existing lab result.")
    public ResponseEntity<LabResultResponseDTO> updateLabResult(
            @PathVariable UUID id,
            @Valid @RequestBody LabResultRequestDTO requestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.updateLabResult(id, requestDTO, locale));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Release Lab Result", description = "Marks the lab result as released and ready for downstream workflows.")
    public ResponseEntity<LabResultResponseDTO> releaseLabResult(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.releaseLabResult(id, locale));
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAnyRole('DOCTOR', 'MIDWIFE', 'LAB_SCIENTIST')")
    @Operation(summary = "Sign Lab Result", description = "Captures a clinician signature for the lab result.")
    public ResponseEntity<LabResultResponseDTO> signLabResult(
            @PathVariable UUID id,
            @RequestBody(required = false) LabResultSignatureRequestDTO request,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.signLabResult(id, request, locale));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'SUPER_ADMIN')")
    @Operation(summary = "Acknowledge Lab Result", description = "Marks the lab result as acknowledged. Currently idempotent for synthetic dashboard data.")
    public ResponseEntity<Void> acknowledgeLabResult(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        labResultService.acknowledgeLabResult(id, locale);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST')")
    @Operation(summary = "Delete Lab Result", description = "Deletes a lab result by ID.")
    public ResponseEntity<String> deleteLabResult(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        labResultService.deleteLabResult(id, locale);
        String message = messageSource.getMessage("labresult.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(message);
    }

    // ==================== Enhanced Trending Endpoints (Story #5) ====================

    @GetMapping("/{id}/compare")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Compare Lab Result with Previous", description = "Compares current lab result with the previous measurement to identify trends and significant changes.")
    public ResponseEntity<LabResultComparisonDTO> compareLabResult(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.compareLabResults(id, locale));
    }

    @GetMapping("/patient/{patientId}/test/{testDefinitionId}/compare-sequential")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE')")
    @Operation(summary = "Compare Sequential Lab Results", description = "Compares all sequential lab results for a specific test and patient to identify long-term trends.")
    public ResponseEntity<List<LabResultComparisonDTO>> compareSequentialResults(
            @PathVariable UUID patientId,
            @PathVariable UUID testDefinitionId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.compareSequentialResults(patientId, testDefinitionId, locale));
    }

    @GetMapping("/hospital/{hospitalId}/critical")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Get Critical Lab Results", description = "Retrieves all critical lab results for a hospital since a specified time.")
    public ResponseEntity<List<LabResultResponseDTO>> getCriticalResults(
            @PathVariable UUID hospitalId,
            @RequestParam(name = "since", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        LocalDateTime sinceTime = since != null ? since : LocalDateTime.now().minusHours(24);
        return ResponseEntity.ok(labResultService.getCriticalResults(hospitalId, sinceTime, locale));
    }

    @GetMapping("/hospital/{hospitalId}/critical/unacknowledged")
    @PreAuthorize("hasAnyRole('DOCTOR', 'LAB_SCIENTIST', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Get Unacknowledged Critical Results", description = "Retrieves critical lab results that require acknowledgment - used for alert dashboards.")
    public ResponseEntity<List<LabResultResponseDTO>> getUnacknowledgedCriticalResults(
            @PathVariable UUID hospitalId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(labResultService.getCriticalResultsRequiringAcknowledgment(hospitalId, locale));
    }
}
