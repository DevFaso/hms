package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.PatientLabResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

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
        requireAuth(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, true);
        int safeLimit = sanitizeLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);

        log.info("GET /api/patients/{}/lab-results - hospital: {}", patientId, resolvedHospitalId);
        List<PatientLabResultResponseDTO> results = patientLabResultService
            .getLabResultsForPatient(patientId, resolvedHospitalId, safeLimit);
        return ResponseEntity.ok(results);
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
                        // Fall through to other claims.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private UUID resolveHospitalScope(Authentication auth, UUID requestedHospitalId, boolean required) {
        UUID jwtHospitalId = extractHospitalIdFromJwt(auth);

        if (hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElseGet(() -> required ? throwMissingHospital() : null);
        }

        if (hasAuthority(auth, "ROLE_RECEPTIONIST")) {
            return resolveReceptionistScope(auth, requestedHospitalId, jwtHospitalId, required);
        }

        if (hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")) {
            return preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElseGet(() -> required ? throwMissingHospital() : null);
        }

        return preferHospital(requestedHospitalId, jwtHospitalId)
            .or(() -> fallbackHospitalFromAssignments(auth))
            .orElseGet(() -> required ? throwMissingHospital() : null);
    }

    private UUID throwMissingHospital() {
        throw new BusinessException("Hospital context is required to view lab results.");
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
            throw new BusinessException("Receptionist must provide a hospital context (token or request).");
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
                    // Claim value was not a valid UUID string; continue.
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
                    // Ignore invalid string value.
                }
            }
        }
        return null;
    }

    private int sanitizeLimit(Integer candidate, int defaultValue, int maxValue) {
        int value = candidate == null ? defaultValue : candidate;
        if (value < 1) {
            value = 1;
        }
        return Math.min(value, maxValue);
    }
}
