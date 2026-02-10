package com.example.hms.controller;

import com.example.hms.payload.dto.medication.MedicationTimelineResponseDTO;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import com.example.hms.service.MedicationHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * REST Controller for Medication History operations.
 * Provides endpoints for medication timeline, pharmacy fill management,
 * overlap detection, and drug-drug interaction checking.
 */
@RestController
@RequestMapping("/medication-history")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Medication History", description = "Medication history timeline, pharmacy fills, and drug interaction checking")
@SecurityRequirement(name = "bearerAuth")
public class MedicationHistoryController {

    private final MedicationHistoryService medicationHistoryService;

    /**
     * Get comprehensive medication timeline for a patient.
     * Includes prescriptions, pharmacy fills, overlap detection, drug interactions,
     * and polypharmacy assessment.
     *
     * @param patientId Patient ID
     * @param hospitalId Hospital ID
     * @param startDate Optional start date filter (yyyy-MM-dd)
     * @param endDate Optional end date filter (yyyy-MM-dd)
     * @param locale User locale for i18n
     * @return Comprehensive medication timeline with analysis
     */
    @GetMapping("/patient/{patientId}/timeline")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST', 'ROLE_LAB_SCIENTIST')")
    @Operation(
        summary = "Get medication timeline",
        description = "Retrieves comprehensive medication timeline including prescriptions, pharmacy fills, " +
                     "overlap detection, drug interactions, and polypharmacy assessment"
    )
    public ResponseEntity<MedicationTimelineResponseDTO> getMedicationTimeline(
            @PathVariable UUID patientId,
            @RequestParam UUID hospitalId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Locale locale) {

        log.info("GET /api/medication-history/patient/{}/timeline - Hospital: {}", patientId, hospitalId);

        MedicationTimelineResponseDTO timeline = medicationHistoryService.getMedicationTimeline(
            patientId, hospitalId, startDate, endDate, locale
        );

        return ResponseEntity.ok(timeline);
    }

    /**
     * Create a new pharmacy fill record.
     * Used for manual entry or external pharmacy system integration.
     *
     * @param request Pharmacy fill details
     * @param locale User locale for i18n
     * @return Created pharmacy fill
     */
    @PostMapping("/pharmacy-fills")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_PHARMACIST')")
    @Operation(
        summary = "Create pharmacy fill",
        description = "Creates a new pharmacy fill record (manual entry or external system import)"
    )
    public ResponseEntity<PharmacyFillResponseDTO> createPharmacyFill(
            @Valid @RequestBody PharmacyFillRequestDTO request,
            Locale locale) {

        log.info("POST /api/medication-history/pharmacy-fills - Patient: {}, Medication: {}",
            request.getPatientId(), request.getMedicationName());

        PharmacyFillResponseDTO response = medicationHistoryService.createPharmacyFill(request, locale);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get a specific pharmacy fill by ID.
     *
     * @param fillId Pharmacy fill ID
     * @param locale User locale for i18n
     * @return Pharmacy fill details
     */
    @GetMapping("/pharmacy-fills/{fillId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST')")
    @Operation(
        summary = "Get pharmacy fill by ID",
        description = "Retrieves details of a specific pharmacy fill record"
    )
    public ResponseEntity<PharmacyFillResponseDTO> getPharmacyFillById(
            @PathVariable UUID fillId,
            Locale locale) {

        log.info("GET /api/medication-history/pharmacy-fills/{}", fillId);

        PharmacyFillResponseDTO response = medicationHistoryService.getPharmacyFillById(fillId, locale);

        return ResponseEntity.ok(response);
    }

    /**
     * Get all pharmacy fills for a patient.
     *
     * @param patientId Patient ID
     * @param hospitalId Hospital ID
     * @param locale User locale for i18n
     * @return List of pharmacy fills
     */
    @GetMapping("/patient/{patientId}/pharmacy-fills")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST')")
    @Operation(
        summary = "Get pharmacy fills for patient",
        description = "Retrieves all pharmacy fill records for a specific patient"
    )
    public ResponseEntity<List<PharmacyFillResponseDTO>> getPharmacyFillsByPatient(
            @PathVariable UUID patientId,
            @RequestParam UUID hospitalId,
            Locale locale) {

        log.info("GET /api/medication-history/patient/{}/pharmacy-fills - Hospital: {}", patientId, hospitalId);

        List<PharmacyFillResponseDTO> response = medicationHistoryService.getPharmacyFillsByPatient(
            patientId, hospitalId, locale
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Update an existing pharmacy fill record.
     *
     * @param fillId Pharmacy fill ID
     * @param request Updated pharmacy fill details
     * @param locale User locale for i18n
     * @return Updated pharmacy fill
     */
    @PutMapping("/pharmacy-fills/{fillId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_PHARMACIST')")
    @Operation(
        summary = "Update pharmacy fill",
        description = "Updates an existing pharmacy fill record"
    )
    public ResponseEntity<PharmacyFillResponseDTO> updatePharmacyFill(
            @PathVariable UUID fillId,
            @Valid @RequestBody PharmacyFillRequestDTO request,
            Locale locale) {

        log.info("PUT /api/medication-history/pharmacy-fills/{}", fillId);

        PharmacyFillResponseDTO response = medicationHistoryService.updatePharmacyFill(fillId, request, locale);

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a pharmacy fill record.
     *
     * @param fillId Pharmacy fill ID
     * @param locale User locale for i18n
     * @return 204 No Content
     */
    @DeleteMapping("/pharmacy-fills/{fillId}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Delete pharmacy fill",
        description = "Deletes a pharmacy fill record (admin only)"
    )
    public ResponseEntity<Void> deletePharmacyFill(
            @PathVariable UUID fillId,
            Locale locale) {

        log.info("DELETE /api/medication-history/pharmacy-fills/{}", fillId);

        medicationHistoryService.deletePharmacyFill(fillId, locale);

        return ResponseEntity.noContent().build();
    }
}
