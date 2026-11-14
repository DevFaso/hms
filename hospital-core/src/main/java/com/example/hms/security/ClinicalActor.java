package com.example.hms.security;

import com.example.hms.config.SecurityConstants;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * User decision 2026-11-14 (imaging access): a clinical act is done through a clinical role the
 * caller holds on their own, never through an administrative one.
 *
 * <p>{@link RoleExpansion#SUPER_ADMIN_INHERITS} gives every super-admin ROLE_DOCTOR and ROLE_NURSE
 * on every request, so {@code hasAnyRole('DOCTOR')} admits a super-admin who has never been a
 * doctor. {@link #holdsOwnRole} answers with the roles the super-admin rule did not add: the
 * caller's live assignments ({@link HospitalContext#getAssignedRoles()}) plus every token role
 * outside the inheritance list, with the doctor equivalence. For anyone else it is their
 * authorities as they are.
 *
 * <p>Static, so a {@code @PreAuthorize} reaches it with {@code T(...)} and no
 * {@code @WebMvcTest} slice needs a bean for it.
 */
public final class ClinicalActor {

    /** The administrative roles that may read imaging but never act on it. */
    public static final Set<String> ADMIN_ROLES = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN, SecurityConstants.ROLE_HOSPITAL_ADMIN, SecurityConstants.ROLE_ADMIN);

    /** The clinical roles any imaging act or full imaging read goes through. */
    public static final Set<String> IMAGING_CLINICAL_ROLES = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_NURSE, SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RADIOLOGIST);

    private ClinicalActor() {
    }

    /**
     * Whether the caller holds one of {@code roles} (bare, {@code 'DOCTOR'}, or prefixed) on their
     * own — not only because they are a super-admin.
     */
    public static boolean holdsOwnRole(Authentication authentication, String... roles) {
        Set<String> own = ownRoles(authentication);
        for (String role : roles) {
            if (role != null && own.contains(role.startsWith("ROLE_") ? role : "ROLE_" + role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The current caller reads imaging as an administrator only: they hold an administrative role
     * and no clinical imaging role of their own. Such a caller sees orders and released reports of
     * the hospitals they act at, nothing in progress and nothing across hospitals.
     */
    public static boolean isAdministrativeImagingReader() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> own = ownRoles(authentication);
        boolean admin = authorities(authentication).stream().anyMatch(ADMIN_ROLES::contains);
        return admin && own.stream().noneMatch(IMAGING_CLINICAL_ROLES::contains);
    }

    static Set<String> ownRoles(Authentication authentication) {
        List<String> authorities = authorities(authentication);
        if (authorities.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> assigned = HospitalContextHolder.getContext()
            .map(HospitalContext::getAssignedRoles)
            .orElse(Collections.emptySet());
        // verifiedSuperAdmin=false: reconcile then drops every role only the super-admin rule gave.
        return RoleExpansion.reconcile(authorities, false, assigned == null ? Set.of() : assigned);
    }

    private static List<String> authorities(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .toList();
    }
}
