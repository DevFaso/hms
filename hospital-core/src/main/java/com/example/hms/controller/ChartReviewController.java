package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.service.ChartReviewService;
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
 * Read-only endpoint backing the Chart Review tabbed viewer
 * (Encounters / Notes / Results / Medications / Imaging / Procedures
 * + unified timeline). Returns one aggregated payload per patient so
 * the UI can render the chart in a single round-trip on metered links.
 *
 * <p>Authorization mirrors the Storyboard endpoint: any clinical or
 * administrative role that can already open a patient chart can read
 * the chart-review summary. Tenant scoping is enforced in the controller
 * because the underlying repository helpers are derived queries (not
 * Specifications) and cannot apply tenant filters on their own.
 */
@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@Tag(name = "Patient Chart Review", description = "Aggregated tabbed viewer (encounters / notes / results / medications / imaging / procedures + timeline)")
public class ChartReviewController {

    private final ChartReviewService chartReviewService;
    private final ControllerAuthUtils authUtils;

    @Operation(
        summary = "Get patient chart-review aggregate",
        description = "Returns the six chart-review sections plus a unified timeline for the "
            + "patient. Optional hospital scope is validated against the caller's active "
            + "assignments. The {@code limit} parameter caps each section (default 20, "
            + "min 5, max 100) so the response stays small on metered networks.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "200", description = "Chart review payload returned",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = ChartReviewDTO.class)))
    @GetMapping("/{patientId}/chart-review")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_PHARMACIST',"
        + "'ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER',"
        + "'ROLE_LAB_DIRECTOR','ROLE_QUALITY_MANAGER','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ChartReviewDTO> getChartReview(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        // Validate the requested hospital is one the caller is assigned to (or
        // they are SUPER_ADMIN). The repository helpers used downstream are
        // derived queries, not Specifications, so we must enforce scope here.
        UUID effectiveHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(
            chartReviewService.getChartReview(patientId, effectiveHospitalId, limit));
    }
}
