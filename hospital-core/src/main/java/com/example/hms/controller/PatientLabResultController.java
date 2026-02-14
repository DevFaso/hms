package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.service.PatientLabResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/patients/{patientId}/lab-results")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Patient Lab Results", description = "Retrieve simplified lab results for patient dashboards")
public class PatientLabResultController {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final PatientLabResultService patientLabResultService;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_LAB_SCIENTIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_MIDWIFE')")
    @Operation(
        summary = "List patient lab results",
        description = "Returns simplified lab result summaries for the selected patient, scoped to the caller's hospital context.",
        security = @SecurityRequirement(name = "bearerAuth"),
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Lab results retrieved",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = PatientLabResultResponseDTO.class)))
            )
        }
    )
    public ResponseEntity<List<PatientLabResultResponseDTO>> listLabResults(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, true);
        int safeLimit = authUtils.sanitizeLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);

        log.info("GET /api/patients/{}/lab-results - hospital: {}", patientId, resolvedHospitalId);
        List<PatientLabResultResponseDTO> results = patientLabResultService
            .getLabResultsForPatient(patientId, resolvedHospitalId, safeLimit);
        return ResponseEntity.ok(results);
    }
}
