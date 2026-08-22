package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.GrowthChartDTO;
import com.example.hms.service.GrowthChartService;
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

import java.util.UUID;

/**
 * Read-only anthropometric series for the patient-chart Growth tab.
 *
 * <p>Roles mirror the vitals read endpoints. NOTE: the SecurityConfig GET
 * matcher for {@code /patients/*}{@code /vitals/**} is shadowed by the broad
 * {@code GET /patients/**} matcher (first-match-wins), so for GETs under
 * /patients the {@code @PreAuthorize} below is the authoritative gate — it is
 * not optional hardening.
 */
@RestController
@RequestMapping("/patients/{patientId}/growth-chart")
@RequiredArgsConstructor
@Tag(name = "Growth Chart", description = "Anthropometric history (weight, height, head circumference) over age")
public class GrowthChartController {

    private final GrowthChartService growthChartService;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Anthropometric growth series for a patient",
        description = "Weight, height and head-circumference points over age, seeded with the linked "
            + "delivery record's birth weight for single-infant deliveries. Percentile curves are "
            + "deliberately absent until a verified WHO reference dataset is imported.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<GrowthChartDTO> getGrowthChart(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(growthChartService.getGrowthChart(patientId, resolvedHospitalId));
    }
}
