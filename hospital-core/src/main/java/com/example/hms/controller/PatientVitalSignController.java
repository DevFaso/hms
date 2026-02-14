package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
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
@RequestMapping("/patients/{patientId}/vitals")
@RequiredArgsConstructor
@Tag(name = "Patient Vital Signs", description = "Record and retrieve patient vital signs")
public class PatientVitalSignController {

    private static final int DEFAULT_RECENT_LIMIT = 10;
    private static final int MAX_RECENT_LIMIT = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final PatientVitalSignService patientVitalSignService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

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
        requireAuth(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        UUID recorderUserId = resolveUserId(auth).orElse(null);
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
        requireAuth(auth);
        int effectiveLimit = sanitizeLimit(limit, DEFAULT_RECENT_LIMIT, MAX_RECENT_LIMIT);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
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
        requireAuth(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, false);
        LocalDateTime fromDate = parseDateTime(from);
        LocalDateTime toDate = parseDateTime(to);
        int safeSize = sanitizeLimit(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<PatientVitalSignResponseDTO> vitals = patientVitalSignService
            .getVitals(patientId, resolvedHospitalId, fromDate, toDate, safePage, safeSize);
        return ResponseEntity.ok(vitals);
    }

    private void requireAuth(Authentication auth) {
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
                    // Argument not valid - skip
                }
                }
            }
        }
        return Optional.empty();
    }

    private UUID resolveHospitalScope(Authentication auth,
                                      UUID queryHospitalId,
                                      UUID bodyHospitalId,
                                      boolean requiredForReceptionist) {
        UUID requestedHospitalId = queryHospitalId != null ? queryHospitalId : bodyHospitalId;
        UUID jwtHospitalId = extractHospitalIdFromJwt(auth);

        if (hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        if (hasAuthority(auth, "ROLE_RECEPTIONIST")) {
            return resolveReceptionistScope(auth, requestedHospitalId, jwtHospitalId, requiredForReceptionist);
        }

        if (hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
        }

        return preferHospital(requestedHospitalId, jwtHospitalId)
            .or(() -> fallbackHospitalFromAssignments(auth))
            .orElse(null);
    }

    private UUID resolveReceptionistScope(Authentication auth,
                                           UUID requestedHospitalId,
                                           UUID jwtHospitalId,
                                           boolean required) {
        if (jwtHospitalId != null) {
            return jwtHospitalId;
        }
        if (requestedHospitalId != null) {
            return requestedHospitalId;
        }
        Optional<UUID> assignmentHospital = fallbackHospitalFromAssignments(auth);
        if (assignmentHospital.isPresent()) {
            return assignmentHospital.get();
        }
        if (required) {
            throw new BusinessException("Receptionist must be affiliated with a hospital (provide hospitalId in token or request).");
        }
        return null;
    }

    private Optional<UUID> preferHospital(UUID requestedHospitalId, UUID jwtHospitalId) {
        if (requestedHospitalId != null) {
            return Optional.of(requestedHospitalId);
        }
        if (jwtHospitalId != null) {
            return Optional.of(jwtHospitalId);
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
        return auth.getAuthorities().stream().anyMatch(granted -> authority.equalsIgnoreCase(granted.getAuthority()));
    }

    private UUID extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String claim = jwt.getClaimAsString("hospitalId");
            if (claim != null && !claim.isBlank()) {
                try {
                    return UUID.fromString(claim);
                } catch (IllegalArgumentException ignored) {
                    // Argument not valid - skip
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
                    // Argument not valid - skip
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
