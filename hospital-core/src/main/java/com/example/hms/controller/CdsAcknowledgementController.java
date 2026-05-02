package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.cds.CdsAcknowledgementRequestDTO;
import com.example.hms.payload.dto.cds.CdsAcknowledgementResponseDTO;
import com.example.hms.service.CdsAcknowledgementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for clinicians to dismiss or override Best-Practice Advisory cards.
 * The recorded acknowledgements drive suppression on subsequent CDS evaluations
 * (the rule engine consults {@link CdsAcknowledgementService#activeForPatient}).
 */
@RestController
@RequestMapping("/cds-acknowledgements")
@RequiredArgsConstructor
@Tag(name = "CDS Acknowledgements", description = "Clinician dismissals and overrides of Best-Practice Advisories")
public class CdsAcknowledgementController {

    private static final String CLINICIAN_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final CdsAcknowledgementService service;

    @Operation(summary = "Record an acknowledgement / override of a CDS card")
    @PostMapping
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<ApiResponseWrapper<CdsAcknowledgementResponseDTO>> record(
            Authentication auth,
            @Valid @RequestBody CdsAcknowledgementRequestDTO request) {
        CdsAcknowledgementResponseDTO result = service.record(auth, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    @Operation(summary = "List active acknowledgements for a patient",
            description = "Used by the rule engine and the BPA panel to know which cards to suppress.")
    @GetMapping
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<ApiResponseWrapper<List<CdsAcknowledgementResponseDTO>>> active(
            @RequestParam UUID patientId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.activeForPatient(patientId)));
    }
}
