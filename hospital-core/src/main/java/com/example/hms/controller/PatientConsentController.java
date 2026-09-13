package com.example.hms.controller;

import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.service.PatientConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * E9 #65 (decision D7) — the consent-grant machinery is retired: a patient's
 * record follows them on the treatment relationship, and release beyond it
 * goes through ROI. What remains here is read-only, for one release, so the
 * rows granted before the change can still be reconciled against their
 * {@code CONSENT_GRANTED} / {@code CONSENT_REVOKED} audit events. Nothing
 * writes {@code clinical.patient_consents} any more.
 */
@RestController
@RequestMapping("/patient-consents")
@RequiredArgsConstructor
@Tag(name = "Patient Consents", description = "Read-only listing of the record-sharing consents granted before E9 #65.")
public class PatientConsentController {

    private final PatientConsentService patientConsentService;

    @GetMapping
    @Operation(summary = "List All Patient Consents", description = "Returns a paginated list of all patient consents.")
    @ApiResponse(responseCode = "200", description = "Paginated list of consents.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getAllConsents(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getAllConsents(pageable));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "List Consents by Patient", description = "Returns all consents for the given patient.")
    @ApiResponse(responseCode = "200", description = "List of consents for patient.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getConsentsByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getConsentsByPatient(patientId, pageable));
    }
}
