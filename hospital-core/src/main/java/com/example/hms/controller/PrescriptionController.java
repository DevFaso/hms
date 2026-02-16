package com.example.hms.controller;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.service.PrescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/prescriptions")
@Tag(name = "Prescription Management", description = "APIs for managing prescriptions")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Create Prescription", description = "Creates a new prescription (doctor or hospital admin).")
    public ResponseEntity<PrescriptionResponseDTO> create(
        @Valid @RequestBody PrescriptionRequestDTO request,
        Locale locale) {
        PrescriptionResponseDTO created = prescriptionService.createPrescription(request, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_PATIENT')")
    @Operation(summary = "Get Prescription by ID", description = "Fetch a prescription by ID.")
    public ResponseEntity<PrescriptionResponseDTO> getById(
        @PathVariable UUID id,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.getPrescriptionById(id, locale));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Search/List Prescriptions", description = "List prescriptions with optional filters + pagination.")
    public ResponseEntity<Page<PrescriptionResponseDTO>> list(
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) UUID encounterId,
        @ParameterObject Pageable pageable,
        Locale locale) {
        return ResponseEntity.ok(
            prescriptionService.list(patientId, staffId, encounterId, pageable, locale)
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Update Prescription", description = "Updates an existing prescription.")
    public ResponseEntity<PrescriptionResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody PrescriptionRequestDTO request,
        Locale locale) {
        return ResponseEntity.ok(prescriptionService.updatePrescription(id, request, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Hard Delete (Super Admin only)", description = "Physically deletes a prescription.")
    public ResponseEntity<String> delete(
        @PathVariable UUID id,
        Locale locale) {
        prescriptionService.deletePrescription(id, locale);
        String message = messageSource.getMessage("prescription.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(message);
    }
}
