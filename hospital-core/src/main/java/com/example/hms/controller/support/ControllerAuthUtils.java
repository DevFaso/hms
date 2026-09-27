package com.example.hms.controller.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.security.PrincipalUserIds;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared authentication and hospital-scope utilities for REST controllers.
 *
 * <p>The scope methods are thin adapters over the one tenant resolver,
 * {@link ActingScopeResolver} (docs/security/tenant-resolution.md §4.1): a
 * requested hospital narrows the request's scope once ({@code narrowTo}),
 * otherwise the request's own scope is read. So a controller-resolved scope
 * and a service-resolved scope ({@code RoleValidator.requireActiveHospitalId})
 * are the same answer, including for a super-admin's {@code X-Hospital-Id},
 * which this class used to ignore (D1), and nothing here falls back to a
 * "newest" or "primary" assignment.
 */
@Component
@RequiredArgsConstructor
public class ControllerAuthUtils {

    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";

    private final ActingScopeResolver actingScopeResolver;

    /**
     * Require non-null authentication; throws {@link BusinessException} otherwise.
     */
    public void requireAuth(Authentication auth) {
        if (auth == null) {
            throw new BusinessException("Authentication required.");
        }
    }

    /**
     * The caller's local user id: {@link PrincipalUserIds}, the one rule for
     * both auth paths (a Keycloak principal's {@code appUserId} claim, never
     * its {@code sub}). Services call {@link PrincipalUserIds} directly.
     */
    public Optional<UUID> resolveUserId(Authentication auth) {
        return PrincipalUserIds.of(auth);
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
     * The hospital this request acts at, narrowed to {@code requestedHospitalId}
     * when the controller received one.
     * <ul>
     *   <li>A requested hospital: a verified super-admin may name any; anyone
     *       else, receptionists included (who used to be silently given their
     *       context hospital instead, D12), only one they hold live. Otherwise
     *       403 ({@link HospitalScopeRefusedException}), audited.</li>
     *   <li>None requested: the request's scope, i.e. the {@code X-Hospital-Id}
     *       hospital or the only one held; {@code null} for a super-admin in
     *       global view.</li>
     *   <li>A caller holding several hospitals who named none is refused with
     *       {@link RoleValidator#HOSPITAL_CONTEXT_REQUIRED} (design Q2, option
     *       A), never given the newest assignment.</li>
     *   <li>No hospital at all: {@code null}, or for a receptionist when
     *       {@code requiredForReceptionist}, a refusal.</li>
     * </ul>
     *
     * @param auth                       current authentication
     * @param requestedHospitalId        the caller-supplied hospital ID (nullable)
     * @param requiredForReceptionist    whether the receptionist role requires a hospital context
     * @return the resolved hospital ID, or {@code null} for global view or when none is held and none is required
     */
    public UUID resolveHospitalScope(Authentication auth,
                                     UUID requestedHospitalId,
                                     boolean requiredForReceptionist) {
        ActingScope scope = requestedHospitalId != null
            ? actingScopeResolver.narrowTo(requestedHospitalId)
            : actingScopeResolver.current();
        return switch (scope) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> null;
            case ActingScope.Refused(ActingScope.Reason reason) -> refusedScope(auth, reason, requiredForReceptionist);
        };
    }

    private UUID refusedScope(Authentication auth, ActingScope.Reason reason, boolean requiredForReceptionist) {
        switch (reason) {
            case NOT_PERMITTED, NO_LONGER_PERMITTED ->
                throw new HospitalScopeRefusedException(reason, ActingScopeResolver.refusalMessage(reason));
            case AMBIGUOUS -> throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
            default -> {
                if (requiredForReceptionist && hasAuthority(auth, ROLE_RECEPTIONIST)) {
                    throw new BusinessException(
                        "Receptionist must be affiliated with a hospital (select an active hospital or provide hospitalId).");
                }
                return null;
            }
        }
    }

    /**
     * The hospital this request acts at, or {@code null} for a super-admin in
     * global view or a caller with none: the resolver's answer, read without
     * a requested hospital.
     */
    public UUID contextHospitalId() {
        return actingScopeResolver.current() instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    /**
     * {@link #contextHospitalId()}. It used to add a "newest assignment"
     * fallback for callers the filter had not reached; the resolver's
     * {@code SOLE_ASSIGNMENT} rule replaces it, and a caller with several
     * hospitals and none named has none.
     */
    @SuppressWarnings("java:S1172") // kept for its callers: the scope no longer depends on the principal
    public UUID currentHospitalId(Authentication auth) {
        return contextHospitalId();
    }

    /**
     * Check whether the authentication has a given granted authority (case-insensitive).
     */
    public boolean hasAuthority(Authentication auth, String authority) {
        // getAuthorities() is non-null by the Authentication contract, so only
        // the authentication itself can be absent.
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .anyMatch(granted -> authority.equalsIgnoreCase(granted.getAuthority()));
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
