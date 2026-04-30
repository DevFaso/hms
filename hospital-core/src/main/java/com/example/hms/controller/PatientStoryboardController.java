package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;
import com.example.hms.service.PatientStoryboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

import java.util.UUID;

/**
 * Read-only endpoint backing the persistent Storyboard patient banner.
 * Returns a small DTO covering allergies, active problems, the most recent
 * non-terminal encounter, and resuscitation/advance-directive state — all of
 * which already live in the existing clinical tables.
 *
 * <p>Authorization mirrors the rest of the patient APIs: any clinical or
 * administrative role that can already open a patient chart can read the
 * storyboard. Tenant scoping is enforced by the underlying repositories
 * (no new tenant rules introduced).
 */
@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@Tag(name = "Patient Storyboard", description = "Persistent banner summary (allergies/problems/active encounter/code status)")
public class PatientStoryboardController {

    private final PatientStoryboardService patientStoryboardService;
    private final ControllerAuthUtils authUtils;

    @Operation(
        summary = "Get patient storyboard summary",
        description = "Returns the persistent banner data for a patient: active allergies, "
            + "active problems, the most recent non-terminal encounter, and code-status / "
            + "advance directives. Optionally scoped to a hospital.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Storyboard summary returned",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientStoryboardDTO.class)))
    @GetMapping("/{patientId}/storyboard")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_PHARMACIST',"
        + "'ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER',"
        + "'ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientStoryboardDTO> getStoryboard(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID effectiveHospitalId = hospitalId != null
            ? hospitalId
            : authUtils.extractHospitalIdFromJwt(auth);
        return ResponseEntity.ok(patientStoryboardService.getStoryboard(patientId, effectiveHospitalId));
    }
}
