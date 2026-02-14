package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;
import com.example.hms.service.PostpartumCareService;
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
@RequestMapping("/patients/{patientId}/postpartum")
@RequiredArgsConstructor
@Tag(name = "Postpartum Care", description = "Manage postpartum observation workflow including alerts and scheduling")
public class PostpartumCareController {

    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final PostpartumCareService postpartumCareService;
    private final ControllerAuthUtils authUtils;

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Record a postpartum observation",
        description = "Creates a postpartum observation entry capturing vitals, uterine assessments, education, and follow-up actions.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(responseCode = "201", description = "Observation recorded",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = PostpartumObservationResponseDTO.class)))
    public ResponseEntity<PostpartumObservationResponseDTO> recordObservation(
        @PathVariable UUID patientId,
        @Valid @RequestBody PostpartumObservationRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID carePlanId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        if (carePlanId != null) {
            request.setCarePlanId(carePlanId);
        }
        PostpartumObservationResponseDTO response = postpartumCareService.recordObservation(patientId, request, recorderUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/observations/recent")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "List recent postpartum observations",
        description = "Returns the most recent postpartum observations for the patient, including schedule snapshot.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<PostpartumObservationResponseDTO>> getRecentObservations(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID carePlanId,
        @RequestParam(required = false, defaultValue = "10") Integer limit,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        int effectiveLimit = authUtils.sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        List<PostpartumObservationResponseDTO> observations = postpartumCareService.getRecentObservations(
            patientId,
            resolvedHospitalId,
            carePlanId,
            effectiveLimit
        );
        return ResponseEntity.ok(observations);
    }

    @GetMapping("/observations")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "Search postpartum observations",
        description = "Returns a paginated set of postpartum observations filtered by time window and care plan context.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<List<PostpartumObservationResponseDTO>> searchObservations(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID carePlanId,
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
        List<PostpartumObservationResponseDTO> observations = postpartumCareService.searchObservations(
            patientId,
            resolvedHospitalId,
            carePlanId,
            fromDate,
            toDate,
            safePage,
            safeSize
        );
        return ResponseEntity.ok(observations);
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    @Operation(
        summary = "View postpartum monitoring schedule",
        description = "Returns the active postpartum schedule including next due observation and overdue status.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<PostpartumScheduleDTO> getSchedule(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) UUID carePlanId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        PostpartumScheduleDTO schedule = postpartumCareService.getSchedule(patientId, resolvedHospitalId, carePlanId);
        return ResponseEntity.ok(schedule);
    }
}
