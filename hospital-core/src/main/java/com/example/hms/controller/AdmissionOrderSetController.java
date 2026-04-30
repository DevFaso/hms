package com.example.hms.controller;

import com.example.hms.payload.dto.AdmissionOrderSetRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.orderset.ApplyOrderSetRequestDTO;
import com.example.hms.payload.dto.orderset.AppliedOrderSetSummaryDTO;
import com.example.hms.service.AdmissionOrderSetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * REST endpoints for CPOE order-set templates and their application
 * to admissions. Admin endpoints (POST/PUT/DELETE) require
 * HOSPITAL_ADMIN; the apply endpoint is open to clinicians who
 * normally place orders.
 */
@RestController
@RequestMapping("/order-sets")
public class AdmissionOrderSetController {

    private static final String ADMIN_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";
    private static final String CLINICIAN_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final AdmissionOrderSetService service;

    public AdmissionOrderSetController(AdmissionOrderSetService service) {
        this.service = service;
    }

    /** Picker / admin list — hospital-scoped, optional name search. */
    @GetMapping
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<Page<AdmissionOrderSetResponseDTO>> list(
        @RequestParam UUID hospitalId,
        @RequestParam(required = false) String search,
        Pageable pageable
    ) {
        return ResponseEntity.ok(service.list(hospitalId, search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<AdmissionOrderSetResponseDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize(CLINICIAN_ROLES)
    public ResponseEntity<List<AdmissionOrderSetResponseDTO>> versions(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getVersionHistory(id));
    }

    @PostMapping
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<AdmissionOrderSetResponseDTO> create(
        @Valid @RequestBody AdmissionOrderSetRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<AdmissionOrderSetResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody AdmissionOrderSetRequestDTO request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<AdmissionOrderSetResponseDTO> deactivate(
        @PathVariable UUID id,
        @RequestParam @NotBlank String reason,
        @RequestParam UUID actingStaffId
    ) {
        return ResponseEntity.ok(service.deactivate(id, reason, actingStaffId));
    }

    /** Apply an order set to an admission, fanning into Prescription / Lab / Imaging. */
    @PostMapping("/{orderSetId}/apply/{admissionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<AppliedOrderSetSummaryDTO> apply(
        @PathVariable UUID orderSetId,
        @PathVariable UUID admissionId,
        @Valid @RequestBody ApplyOrderSetRequestDTO request,
        @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        Locale locale = (acceptLanguage == null || acceptLanguage.isBlank())
            ? Locale.ENGLISH
            : Locale.forLanguageTag(acceptLanguage);
        return ResponseEntity.ok(service.applyToAdmission(admissionId, orderSetId, request, locale));
    }
}
