package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.service.PatientMedicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/patients/{patientId}/medications")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Patient Medications", description = "Retrieve simplified medication summaries for patient dashboards")
public class PatientMedicationController {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final PatientMedicationService patientMedicationService;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "List patient medications",
        description = "Returns simplified medication summaries for the selected patient, scoped to the caller's hospital context.",
        security = @SecurityRequirement(name = "bearerAuth"),
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Medications retrieved",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = PatientMedicationResponseDTO.class)))
            )
        }
    )
    public ResponseEntity<List<PatientMedicationResponseDTO>> listMedications(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        int safeLimit = authUtils.sanitizeLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);

        log.info("GET /api/patients/{}/medications - hospital: {}", patientId, resolvedHospitalId);
        List<PatientMedicationResponseDTO> medications = patientMedicationService
            .getMedicationsForPatient(patientId, resolvedHospitalId, safeLimit);
        return ResponseEntity.ok(medications);
    }
}
