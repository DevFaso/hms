package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.GuarantorRequestDTO;
import com.example.hms.payload.dto.GuarantorResponseDTO;
import com.example.hms.service.PatientGuarantorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Guarantor accounts (P3 #21). Roles mirror the patient-insurance surface
 * (HOSPITAL_ADMIN / RECEPTIONIST / NURSE / DOCTOR / MIDWIFE) — deliberately
 * NO SUPER_ADMIN on the PUT: the {@code PUT /patients/**} filter-chain
 * matcher hard-denies SUPER_ADMIN before any annotation runs, and granting
 * it here would create a button that always 403s. POST subpaths fall to
 * {@code anyRequest().authenticated()}, so those annotations are the sole
 * gate (the house pattern for /patients subresources).
 */
@RestController
@RequestMapping("/patients/{patientId}/guarantors")
@RequiredArgsConstructor
@Tag(name = "Guarantors", description = "Financially responsible parties (deactivate, never delete)")
public class PatientGuarantorController {

    private static final String ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_DOCTOR',"
            + "'ROLE_MIDWIFE')";

    private final PatientGuarantorService guarantorService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ROLES)
    @Operation(summary = "Add a guarantor",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<GuarantorResponseDTO> add(
        @PathVariable UUID patientId,
        @Valid @RequestBody GuarantorRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(guarantorService.add(patientId, scope, request));
    }

    @GetMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "Guarantors for a patient (primary first)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<GuarantorResponseDTO>> list(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(guarantorService.list(patientId, scope));
    }

    @PutMapping("/{guarantorId}")
    @PreAuthorize(ROLES)
    @Operation(summary = "Update a guarantor",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<GuarantorResponseDTO> update(
        @PathVariable UUID patientId,
        @PathVariable UUID guarantorId,
        @Valid @RequestBody GuarantorRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(guarantorService.update(patientId, guarantorId, scope, request));
    }

    @PostMapping("/{guarantorId}/deactivate")
    @PreAuthorize(ROLES)
    @Operation(summary = "Deactivate a guarantor (never delete)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<GuarantorResponseDTO> deactivate(
        @PathVariable UUID patientId,
        @PathVariable UUID guarantorId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(guarantorService.deactivate(patientId, guarantorId, scope));
    }

    @PostMapping("/{guarantorId}/reactivate")
    @PreAuthorize(ROLES)
    @Operation(summary = "Reactivate a guarantor",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<GuarantorResponseDTO> reactivate(
        @PathVariable UUID patientId,
        @PathVariable UUID guarantorId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(guarantorService.reactivate(patientId, guarantorId, scope));
    }
}
