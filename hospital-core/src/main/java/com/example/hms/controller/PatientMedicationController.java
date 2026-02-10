package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

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
        requireAuth(auth);
        UUID resolvedHospitalId = resolveHospitalScope(auth, hospitalId, null, true);
        int safeLimit = sanitizeLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);

        log.info("GET /api/patients/{}/medications - hospital: {}", patientId, resolvedHospitalId);
        List<PatientMedicationResponseDTO> medications = patientMedicationService
            .getMedicationsForPatient(patientId, resolvedHospitalId, safeLimit);
        return ResponseEntity.ok(medications);
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
                        // Claim value was not a valid UUID, fall through to other sources.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private UUID resolveHospitalScope(Authentication auth,
                                      UUID queryHospitalId,
                                      UUID bodyHospitalId,
                                      boolean required) {
        UUID requestedHospitalId = queryHospitalId != null ? queryHospitalId : bodyHospitalId;
        UUID jwtHospitalId = extractHospitalIdFromJwt(auth);

        if (hasAuthority(auth, "ROLE_SUPER_ADMIN")) {
            UUID resolved = preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
            if (resolved == null && required) {
                throw new BusinessException("Hospital context is required to view medication history.");
            }
            return resolved;
        }

        if (hasAuthority(auth, "ROLE_RECEPTIONIST")) {
            return resolveReceptionistScope(auth, requestedHospitalId, jwtHospitalId, required);
        }

        if (hasAuthority(auth, "ROLE_HOSPITAL_ADMIN")) {
            UUID resolved = preferHospital(requestedHospitalId, jwtHospitalId)
                .or(() -> fallbackHospitalFromAssignments(auth))
                .orElse(null);
            if (resolved == null && required) {
                throw new BusinessException("Hospital context is required to view medication history.");
            }
            return resolved;
        }

        UUID resolved = preferHospital(requestedHospitalId, jwtHospitalId)
            .or(() -> fallbackHospitalFromAssignments(auth))
            .orElse(null);

        if (resolved == null && required) {
            throw new BusinessException("Hospital context is required to view medication history.");
        }

        return resolved;
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
                    // Claim value was not a valid UUID, fall through to alternative parsing.
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
                    // Claim value was not a valid UUID string, ignore and return null.
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
