package com.example.hms.controller.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared authentication and hospital-scope resolution utilities for REST controllers.
 * <p>
 * Extracted from the private helper methods that were duplicated across
 * PatientVitalSignController, PatientLabResultController, PatientMedicationController,
 * PostpartumCareController, NewbornAssessmentController, NurseTaskController,
 * PatientController, EncounterController, and PatientEducationController.
 */
@Component
@RequiredArgsConstructor
public class ControllerAuthUtils {

    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /**
     * Require non-null authentication; throws {@link BusinessException} otherwise.
     */
    public void requireAuth(Authentication auth) {
        if (auth == null) {
            throw new BusinessException("Authentication required.");
        }
    }

    /**
     * Resolve the user's UUID from {@link CustomUserDetails} or JWT claims
     * ({@code uid}, {@code userId}, {@code id}, {@code sub}).
     */
    public Optional<UUID> resolveUserId(Authentication auth) {
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
                        // try the next claim key
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve hospital scope with separate query-param and body-param hospital IDs.
     * <p>
     * Used by controllers that accept a hospital ID from both a query parameter
     * and a request-body field (the query-param takes precedence).
     *
     * @param auth                       current authentication
     * @param queryHospitalId            hospital ID from a query parameter (nullable)
     * @param bodyHospitalId             hospital ID from a request body (nullable)
     * @param requiredForReceptionist    whether the receptionist role requires a hospital context
     * @return the resolved hospital ID, or {@code null} if not resolvable and not required
     */
    public UUID resolveHospitalScope(Authentication auth,
                                     UUID queryHospitalId,
                                     UUID bodyHospitalId,
                                     boolean requiredForReceptionist) {
        UUID requestedHospitalId = queryHospitalId != null ? queryHospitalId : bodyHospitalId;
        return resolveHospitalScope(auth, requestedHospitalId, requiredForReceptionist);
    }

    /**
     * Resolve hospital scope with a single requested hospital ID.
     * <p>
     * Rules:
     * <ul>
     *   <li>SUPER_ADMIN / HOSPITAL_ADMIN / default: prefer requested → JWT → assignment fallback</li>
     *   <li>RECEPTIONIST: enforce JWT hospital; if absent, allow requested or assignment; throw if required</li>
     * </ul>
     *
     * @param auth                       current authentication
     * @param requestedHospitalId        the caller-supplied hospital ID (nullable)
     * @param requiredForReceptionist    whether the receptionist role requires a hospital context
     * @return the resolved hospital ID, or {@code null} if not resolvable and not required
     */
    public UUID resolveHospitalScope(Authentication auth,
                                     UUID requestedHospitalId,
                                     boolean requiredForReceptionist) {
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

    /**
     * Resolve hospital for RECEPTIONIST role.
     */
    public UUID resolveReceptionistScope(Authentication auth,
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
            throw new BusinessException(
                "Receptionist must be affiliated with a hospital (provide hospitalId in token or request).");
        }
        return null;
    }

    /**
     * Return the first non-null hospital ID.
     */
    public Optional<UUID> preferHospital(UUID requestedHospitalId, UUID jwtHospitalId) {
        if (requestedHospitalId != null) {
            return Optional.of(requestedHospitalId);
        }
        if (jwtHospitalId != null) {
            return Optional.of(jwtHospitalId);
        }
        return Optional.empty();
    }

    /**
     * Fallback: look up the user's most-recent active hospital assignment.
     */
    public Optional<UUID> fallbackHospitalFromAssignments(Authentication auth) {
        return resolveUserId(auth)
            .flatMap(assignmentRepository::findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc)
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId);
    }

    /**
     * Check whether the authentication has a given granted authority (case-insensitive).
     */
    public boolean hasAuthority(Authentication auth, String authority) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .anyMatch(granted -> authority.equalsIgnoreCase(granted.getAuthority()));
    }

    /**
     * Extract {@code hospitalId} from JWT claims.
     */
    public UUID extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String claim = jwt.getClaimAsString("hospitalId");
            if (claim != null && !claim.isBlank()) {
                try {
                    return UUID.fromString(claim);
                } catch (IllegalArgumentException ignored) {
                    // invalid UUID format — fall through
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
                    // invalid UUID format — fall through
                }
            }
        }
        return null;
    }

    /**
     * Parse an ISO-8601 datetime string, returning {@code null} for blank input.
     *
     * @throws BusinessException if the format is invalid
     */
    public LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid datetime format; expected ISO-8601.");
        }
    }

    /**
     * Clamp a nullable pagination limit to [{@code 1} .. {@code maxValue}], defaulting to
     * {@code defaultValue} when {@code candidate} is {@code null}.
     */
    public int sanitizeLimit(Integer candidate, int defaultValue, int maxValue) {
        int value = candidate == null ? defaultValue : candidate;
        if (value < 1) {
            value = 1;
        }
        return Math.min(value, maxValue);
    }
}
