package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
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
@RequestMapping("/patients/{patientId}/postpartum/newborn-assessments")
@RequiredArgsConstructor
@Tag(name = "Newborn Assessment", description = "Capture and review newborn transition assessments including Apgar, vitals, and education")
public class NewbornAssessmentController {

    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final NewbornAssessmentService newbornAssessmentService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

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
        requireAuthentication(auth);
        UUID recorderUserId = resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
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
        requireAuthentication(auth);
        int effectiveLimit = sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
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
        requireAuthentication(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
        LocalDateTime fromDate = parseDateTime(from);
        LocalDateTime toDate = parseDateTime(to);
        int safeSize = sanitizeLimit(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
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
                        // continue searching for alternative claim names
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
                    // fall through to alternative parsing
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
                    // ignore invalid format
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
