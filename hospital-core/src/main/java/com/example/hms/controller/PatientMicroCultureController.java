package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.service.MicroCultureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Patient-scoped culture reports for the chart's Microbiology tab (P3 #19).
 *
 * <p>NOTE: the broad {@code GET /patients/**} SecurityConfig matcher wins
 * first-match over anything narrower, so the {@code @PreAuthorize} here is
 * the authoritative gate. Every role below is also on that matcher's list —
 * a role admitted here but absent there would be 403'd at the filter chain.
 * PHARMACIST is included deliberately: susceptibility panels drive
 * antibiotic stewardship.
 */
@RestController
@RequestMapping("/patients/{patientId}/micro-cultures")
@RequiredArgsConstructor
@Tag(name = "Microbiology", description = "Culture reports, isolates and susceptibility panels")
public class PatientMicroCultureController {

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN',"
            + "'ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_LAB_DIRECTOR',"
            + "'ROLE_QUALITY_MANAGER','ROLE_PHARMACIST')";

    private final MicroCultureService microCultureService;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Culture reports for a patient, newest first",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<MicroCultureResponseDTO>> getForPatient(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(microCultureService.getForPatient(patientId, scope));
    }
}
