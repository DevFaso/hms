package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

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
        requireAuthentication(auth);
        UUID recorderUserId = resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
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
        requireAuthentication(auth);
        int effectiveLimit = sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
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
        requireAuthentication(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
        LocalDateTime fromDate = parseDateTime(from);
        LocalDateTime toDate = parseDateTime(to);
        int safeSize = sanitizeLimit(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
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
        requireAuthentication(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
        PostpartumScheduleDTO schedule = postpartumCareService.getSchedule(patientId, resolvedHospitalId, carePlanId);
        return ResponseEntity.ok(schedule);
    }

    private void requireAuthentication(Authentication auth) {
        if (auth == null) {
            throw new BusinessException("Authentication required.");
        }
    }

    private Optional<UUID> resolveUserId(Authentication auth) {
        if (auth == null) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof CustomUserDetails details) {
            return Optional.ofNullable(details.getUserId());
        }
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            for (String claim : List.of("uid", "userId", "id", "sub")) {
                String raw = jwt.getClaimAsString(claim);
                if (raw != null && !raw.isBlank()) {
                    try {
                        return Optional.of(UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                        // fall through to next claim
                    }
                }
            }
        }
        return Optional.empty();
    }

    private UUID resolveHospitalScope(Authentication auth,
                                      UUID queryHospitalId,
                                      UUID bodyHospitalId,
                                      boolean requireReceptionistContext) {
        UUID requested = queryHospitalId != null ? queryHospitalId : bodyHospitalId;
        UUID tokenHospital = extractHospitalIdFromJwt(auth);

        if (hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            return preferHospital(requested, tokenHospital)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        if (hasAuthority(auth, "ROLE_RECEPTIONIST")) {
            return resolveReceptionistScope(auth, requested, tokenHospital, requireReceptionistContext);
        }

        if (hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")) {
            return preferHospital(requested, tokenHospital)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        return preferHospital(requested, tokenHospital)
            .or(() -> fallbackHospitalFromAssignments(auth))
            .orElse(null);
    }

    private UUID resolveReceptionistScope(Authentication auth,
                                           UUID requestedHospitalId,
                                           UUID tokenHospitalId,
                                           boolean required) {
        if (tokenHospitalId != null) {
            return tokenHospitalId;
        }
        if (requestedHospitalId != null) {
            return requestedHospitalId;
        }
        Optional<UUID> assignmentHospital = fallbackHospitalFromAssignments(auth);
        if (assignmentHospital.isPresent()) {
            return assignmentHospital.get();
        }
        if (required) {
            throw new BusinessException("Receptionist must be affiliated with a hospital context.");
        }
        return null;
    }

    private Optional<UUID> preferHospital(UUID requestedHospitalId, UUID tokenHospitalId) {
        if (requestedHospitalId != null) {
            return Optional.of(requestedHospitalId);
        }
        if (tokenHospitalId != null) {
            return Optional.of(tokenHospitalId);
        }
        return Optional.empty();
    }

    private Optional<UUID> fallbackHospitalFromAssignments(Authentication auth) {
        return resolveUserId(auth)
            .flatMap(assignmentRepository::findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc)
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId);
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .anyMatch(granted -> authority.equalsIgnoreCase(granted.getAuthority()));
    }

    private UUID extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String claim = jwt.getClaimAsString("hospitalId");
            if (claim != null && !claim.isBlank()) {
                try {
                    return UUID.fromString(claim);
                } catch (IllegalArgumentException ignored) {
                    // fall through to additional parsing below
                }
            }
            Object raw = jwt.getClaims().get("hospitalId");
            if (raw instanceof UUID uuid) {
                return uuid;
            }
            if (raw instanceof String str && !str.isBlank()) {
                try {
                    return UUID.fromString(str);
                } catch (IllegalArgumentException ignored) {
                    // ignore invalid string format
                }
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid datetime format; expected ISO-8601.");
        }
    }

    private int sanitizeLimit(Integer candidate, int defaultValue, int maxValue) {
        int value = candidate == null ? defaultValue : candidate;
        if (value < 1) {
            value = 1;
        }
        return Math.min(value, maxValue);
    }
}
