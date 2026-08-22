package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.payload.dto.TreatmentConsentRequestDTO;
import com.example.hms.payload.dto.TreatmentConsentResponseDTO;
import com.example.hms.service.TreatmentConsentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Consent-to-treat records (P3 #21). A RECORD, not a gate — check-in
 * proceeds with or without one; whether treatment should ever be blocked on
 * a missing consent is a clinical-workflow decision deliberately left open.
 *
 * <p>Filter chain: GET rides the broad {@code GET /patients/**} matcher;
 * POST subpaths fall to {@code anyRequest().authenticated()}, so the
 * annotations here are the authoritative gate.
 */
@RestController
@RequestMapping("/patients/{patientId}/treatment-consents")
@RequiredArgsConstructor
@Tag(name = "Treatment Consent", description = "Consent-to-treat records (revoke, never delete)")
public class TreatmentConsentController {

    private static final String ROLES =
        "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final TreatmentConsentService treatmentConsentService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ROLES)
    @Operation(summary = "Record a consent-to-treat",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TreatmentConsentResponseDTO> record(
        @PathVariable UUID patientId,
        @Valid @RequestBody TreatmentConsentRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(
            treatmentConsentService.record(patientId, scope, actorUserId,
                TreatmentConsentSource.MANUAL, request));
    }

    @GetMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "Consent-to-treat history for a patient, newest first",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<TreatmentConsentResponseDTO>> list(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(treatmentConsentService.getForPatient(patientId, scope));
    }

    @PostMapping("/{consentId}/revoke")
    @PreAuthorize(ROLES)
    @Operation(summary = "Revoke a consent (never delete)",
        description = "A revocation reason is mandatory.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TreatmentConsentResponseDTO> revoke(
        @PathVariable UUID patientId,
        @PathVariable UUID consentId,
        @RequestParam String reason,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(
            treatmentConsentService.revoke(consentId, scope, actorUserId, reason));
    }
}
