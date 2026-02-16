package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.service.NewbornAssessmentService;
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
@RequestMapping("/patients/{patientId}/postpartum/newborn-assessments")
@RequiredArgsConstructor
@Tag(name = "Newborn Assessment", description = "Capture and review newborn transition assessments including Apgar, vitals, and education")
public class NewbornAssessmentController {

    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final NewbornAssessmentService newbornAssessmentService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Document a newborn assessment",
        description = "Records newborn adaptation details including Apgar scores, vitals, physical exam findings, follow-up actions, and parent education.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "201", description = "Assessment recorded",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = NewbornAssessmentResponseDTO.class)))
    public ResponseEntity<NewbornAssessmentResponseDTO> recordAssessment(
        @PathVariable UUID patientId,
        @Valid @RequestBody NewbornAssessmentRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        NewbornAssessmentResponseDTO response = newbornAssessmentService.recordAssessment(patientId, request, recorderUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "List recent newborn assessments",
        description = "Returns the most recent newborn assessments recorded for the patient.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<NewbornAssessmentResponseDTO>> getRecentAssessments(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false, defaultValue = "10") Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        int effectiveLimit = authUtils.sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        List<NewbornAssessmentResponseDTO> responses = newbornAssessmentService.getRecentAssessments(
            patientId,
            resolvedHospitalId,
            effectiveLimit
        );
        return ResponseEntity.ok(responses);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Search newborn assessments",
        description = "Returns a paginated view of newborn assessments filtered by time range.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<NewbornAssessmentResponseDTO>> searchAssessments(
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
        List<NewbornAssessmentResponseDTO> responses = newbornAssessmentService.searchAssessments(
            patientId,
            resolvedHospitalId,
            fromDate,
            toDate,
            safePage,
            safeSize
        );
        return ResponseEntity.ok(responses);
    }
}
