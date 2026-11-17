package com.example.hms.security;

import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one rule for which roles a user actually holds, shared by sign-in
 * ({@link CustomUserDetailsService}), the services that branch on a role and
 * the audit role fallback.
 *
 * <ul>
 *   <li>A role with ANY assignment row is governed by those rows: only an
 *       ACTIVE row grants it.</li>
 *   <li>A role held only through {@code user_roles} (no assignment row at all)
 *       is a legacy fallback and still counts, with one exception:
 *       {@code ROLE_PATIENT}. A patient role is always created with a
 *       hospital assignment, so a bare user-level ROLE_PATIENT on an account
 *       that actively holds a staff role is a leftover of a registration that
 *       re-used the staff account (QA D74/D132). Honouring it hid the
 *       hospital's appointments from a receptionist, forced a role picker at
 *       sign-in and filed audit rows under PATIENT.</li>
 * </ul>
 */
public final class EffectiveRoles {

    public static final String ROLE_PATIENT = "ROLE_PATIENT";

    private EffectiveRoles() {
    }

    /** Role codes (as stored, e.g. {@code ROLE_DOCTOR}) the user effectively holds. */
    public static Set<String> codes(User user, Collection<UserRoleHospitalAssignment> assignments) {
        Collection<UserRoleHospitalAssignment> rows = assignments == null ? Set.of() : assignments;
        Set<String> active = rows.stream()
            .filter(a -> Boolean.TRUE.equals(a.getActive()))
            .map(UserRoleHospitalAssignment::getRole)
            .filter(Objects::nonNull)
            .map(Role::getCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> governed = rows.stream()
            .map(UserRoleHospitalAssignment::getRole)
            .filter(Objects::nonNull)
            .map(Role::getCode)
            .filter(Objects::nonNull)
            .map(EffectiveRoles::normalize)
            .collect(Collectors.toSet());
        boolean activeStaff = active.stream().anyMatch(code -> !ROLE_PATIENT.equalsIgnoreCase(code));

        Set<String> result = new LinkedHashSet<>(active);
        if (user != null && user.getUserRoles() != null) {
            user.getUserRoles().stream()
                .map(UserRole::getRole)
                .filter(Objects::nonNull)
                .map(Role::getCode)
                .filter(Objects::nonNull)
                .filter(code -> !governed.contains(normalize(code)))
                .filter(code -> !(activeStaff && ROLE_PATIENT.equalsIgnoreCase(code)))
                .forEach(result::add);
        }
        return result;
    }

    /** Does the user effectively hold this role code (case-insensitive)? */
    public static boolean holds(User user, Collection<UserRoleHospitalAssignment> assignments, String roleCode) {
        if (roleCode == null) {
            return false;
        }
        return codes(user, assignments).stream().anyMatch(roleCode::equalsIgnoreCase);
    }

    private static String normalize(String code) {
        return code.toUpperCase(Locale.ROOT);
    }
}
