package com.example.hms.controller;

import com.example.hms.payload.dto.TreatmentRequestDTO;
import com.example.hms.payload.dto.TreatmentResponseDTO;
import com.example.hms.service.TreatmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/treatments")
@Tag(name = "Treatment Management", description = "APIs for managing medical treatments")
@SecurityRequirement(name = "hospital-auth")
@RequiredArgsConstructor
public class TreatmentController {

    private final TreatmentService treatmentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    @Operation(summary = "Create a new treatment",
            description = "Creates a new medical treatment. Requires HOSPITAL_ADMIN or DOCTOR role.")
    public ResponseEntity<TreatmentResponseDTO> createTreatment(
            @Valid @RequestBody TreatmentRequestDTO treatmentRequestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale,
            @RequestHeader(name = "X-Effective-Role", required = false) String effectiveRole
    ) {
        TreatmentResponseDTO response = treatmentService.createTreatment(treatmentRequestDTO, locale, effectiveRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'PATIENT')")
    @Operation(summary = "Get all treatments",
            description = "Retrieves all treatments with optional language filtering")
    public ResponseEntity<List<TreatmentResponseDTO>> getAllTreatments(
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        List<TreatmentResponseDTO> treatments = treatmentService.getAllTreatments(locale, language);
        return ResponseEntity.ok(treatments);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'PATIENT')")
    @Operation(summary = "Get treatment by ID",
            description = "Retrieves a specific treatment by ID")
    public ResponseEntity<TreatmentResponseDTO> getTreatmentById(
            @PathVariable UUID id,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        TreatmentResponseDTO treatment = treatmentService.getTreatmentById(id, locale, language);
        return ResponseEntity.ok(treatment);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN') or (hasRole('DOCTOR') and @treatmentSecurityService.isTreatmentCreator(authentication, #id))")
    @Operation(summary = "Update a treatment",
            description = "Updates an existing treatment. Requires HOSPITAL_ADMIN role or being the creator DOCTOR.")
    public ResponseEntity<TreatmentResponseDTO> updateTreatment(
            @PathVariable UUID id,
            @Valid @RequestBody TreatmentRequestDTO treatmentRequestDTO,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        TreatmentResponseDTO updatedTreatment = treatmentService.updateTreatment(id, treatmentRequestDTO, locale);
        return ResponseEntity.ok(updatedTreatment);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @Operation(summary = "Delete a treatment",
            description = "Deletes a treatment by its ID. Requires HOSPITAL_ADMIN role.")
    public ResponseEntity<Void> deleteTreatment(@PathVariable UUID id) {
        treatmentService.deleteTreatment(id);
        return ResponseEntity.noContent().build();
    }
}

