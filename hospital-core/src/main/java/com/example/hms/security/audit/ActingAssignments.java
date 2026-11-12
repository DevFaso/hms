package com.example.hms.security.audit;

import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleNames;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1632: the assignment an audit row is filed under is the one whose role
 * the caller is ACTING under, not the first active row at the hospital.
 *
 * <p>A user who is both a doctor and a lab director signs in with one role
 * and gets a token carrying that role only. Every audit writer used to take
 * {@code findFirstByUser_IdAndHospital_IdAndActiveTrue}, which returns either
 * assignment, so a release done as lab director was logged as DOCTOR — a role
 * that may not release. The acting role is the one authorization used: the
 * {@code ROLE_*} authorities of the request, in token order (the token's own
 * role comes first; {@code RoleExpansion} only appends).
 *
 * <p>Out-of-session safe: only the assignment's own columns and its EAGER role
 * are read. Callers pass the returned assignment's id to the audit service,
 * never the entity.
 */
public final class ActingAssignments {

    private static final String ROLE_PREFIX = "ROLE_";

    private ActingAssignments() {
    }

    /** The bare role codes ({@code LAB_DIRECTOR}) the request is acting under, token order first. */
    public static List<String> actingRoles(Authentication auth) {
        if (auth == null) {
            return List.of();
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .filter(a -> a.startsWith(ROLE_PREFIX))
            .map(RoleNames::bareRole)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * The actor's active assignment at the hospital whose role is the first of
     * {@code actingRoles} held there; when none of them is held there, any
     * active assignment (the hospital is still right, and
     * {@link #roleName} then files the token's role, not the row's).
     */
    public static Optional<UserRoleHospitalAssignment> resolve(UserRoleHospitalAssignmentRepository repository,
                                                               UUID userId, UUID hospitalId,
                                                               Collection<String> actingRoles) {
        if (repository == null || userId == null || hospitalId == null) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> rows =
            repository.findAllByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        if (actingRoles != null) {
            for (String role : actingRoles) {
                String wanted = RoleNames.bareRole(role);
                for (UserRoleHospitalAssignment row : rows) {
                    if (wanted != null && wanted.equalsIgnoreCase(codeOf(row))) {
                        return Optional.of(row);
                    }
                }
            }
        }
        return Optional.of(rows.get(0));
    }

    /** {@link #resolve(UserRoleHospitalAssignmentRepository, UUID, UUID, Collection)} for the request's own roles. */
    public static Optional<UserRoleHospitalAssignment> resolve(UserRoleHospitalAssignmentRepository repository,
                                                               UUID userId, UUID hospitalId, Authentication auth) {
        return resolve(repository, userId, hospitalId, actingRoles(auth));
    }

    /**
     * The request's own first role, before RoleExpansion's inherited roles.
     * An assignment at the hospital may only match an inherited role; it
     * anchors the hospital but must not replace the acting role in the log.
     * Without request roles, use the assignment's role. Null when neither names one.
     */
    public static String roleName(UserRoleHospitalAssignment assignment, Collection<String> actingRoles) {
        String held = assignment == null ? null : codeOf(assignment);
        List<String> acting = actingRoles == null ? List.of()
            : actingRoles.stream().map(RoleNames::bareRole).filter(Objects::nonNull).toList();
        return acting.isEmpty() ? held : acting.get(0);
    }

    private static String codeOf(UserRoleHospitalAssignment assignment) {
        return assignment.getRole() == null ? null : RoleNames.bareRole(assignment.getRole().getCode());
    }
}
