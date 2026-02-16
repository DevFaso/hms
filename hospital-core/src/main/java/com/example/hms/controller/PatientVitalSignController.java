package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.service.PatientVitalSignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/patients/{patientId}/vitals")
@RequiredArgsConstructor
@Tag(name = "Patient Vital Signs", description = "Record and retrieve patient vital signs")
public class PatientVitalSignController {

    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final PatientVitalSignService patientVitalSignService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Record vital signs for a patient",
        description = "Creates a new vital sign record scoped to the caller's hospital context.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "201", description = "Vital sign recorded",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PatientVitalSignResponseDTO.class)))
    public ResponseEntity<PatientVitalSignResponseDTO> recordVital(
        @PathVariable UUID patientId,
        @Valid @RequestBody PatientVitalSignRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        PatientVitalSignResponseDTO response = patientVitalSignService.recordVital(patientId, request, recorderUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "List most recent vitals",
        description = "Returns the most recent vital sign entries for the patient.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<PatientVitalSignResponseDTO>> getRecentVitals(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false, defaultValue = "10") Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        int effectiveLimit = authUtils.sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        List<PatientVitalSignResponseDTO> vitals = patientVitalSignService
            .getRecentVitals(patientId, resolvedHospitalId, effectiveLimit);
        return ResponseEntity.ok(vitals);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Search historical vital signs",
        description = "Returns a paginated set of vital sign entries filtered by time window.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<PatientVitalSignResponseDTO>> listVitals(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        LocalDateTime fromDate = authUtils.parseDateTime(from);
        LocalDateTime toDate = authUtils.parseDateTime(to);
        int safeSize = authUtils.sanitizeLimit(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<PatientVitalSignResponseDTO> vitals = patientVitalSignService
            .getVitals(patientId, resolvedHospitalId, fromDate, toDate, safePage, safeSize);
        return ResponseEntity.ok(vitals);
    }
}
