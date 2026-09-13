package com.example.hms.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.example.hms.config.SecurityConstants.ROLE_DOCTOR;
import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_SCIENTIST;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_SURGEON;

/**
 * E9 #67, decision D6 — the ONE place a principal's roles are widened.
 *
 * <p>Two auth paths used to carry their own copy of this rule and had
 * drifted: the JWT path ({@link JwtTokenProvider#getAuthenticationFromJwt})
 * gave a super-admin seven inherited roles on every request, the
 * password-login path ({@code SecurityConfig.authoritiesMapper}) gave
 * fourteen to the login-time authentication, which fed the login role
 * picker. Both now call {@link #expand}; {@link RoleExpansionTest} fails
 * if either grows a list of its own again.
 *
 * <p>The list kept is the JWT one: it is what every request already runs
 * under, so nothing widens. The login picker shrinks to match.
 *
 * <p>Two rules, and they are the whole of it:
 * <ol>
 *   <li><b>Super-admin inheritance</b> — {@code ROLE_SUPER_ADMIN} also holds
 *       {@link #SUPER_ADMIN_INHERITS}, so per-hospital staff checks admit
 *       the platform operator. Cross-tenant decisions must still use the
 *       discrete {@code isSuperAdmin} claim, never this inflated set (see
 *       {@code RoleValidator.isSuperAdminFromJwtClaim}).</li>
 *   <li><b>Doctor equivalence</b> (2026-08-23 role audit, C2) — a physician
 *       or surgeon IS a doctor; every guard naming {@code ROLE_DOCTOR}
 *       admits them through this rule instead of naming three roles.</li>
 * </ol>
 */
public final class RoleExpansion {

    /** Not a constant in {@code SecurityConstants}: the role is not seeded, only matched. */
    public static final String ROLE_PHYSICIAN = "ROLE_PHYSICIAN";

    /** What a super-admin holds on top of {@code ROLE_SUPER_ADMIN}, in the order it is granted. */
    public static final List<String> SUPER_ADMIN_INHERITS = List.of(
        ROLE_HOSPITAL_ADMIN,
        ROLE_RECEPTIONIST,
        ROLE_DOCTOR,
        ROLE_NURSE,
        ROLE_LAB_SCIENTIST,
        ROLE_STAFF,
        ROLE_PATIENT
    );

    private RoleExpansion() {
    }

    /**
     * The given roles plus what the two rules add. Input order is kept and
     * duplicates collapse; null or blank entries are dropped. Roles are
     * expected with their {@code ROLE_} prefix already applied.
     */
    public static Set<String> expand(Collection<String> roles) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                expanded.add(role.trim());
            }
        }
        if (expanded.contains(ROLE_SUPER_ADMIN)) {
            expanded.addAll(SUPER_ADMIN_INHERITS);
        }
        if (expanded.contains(ROLE_PHYSICIAN) || expanded.contains(ROLE_SURGEON)) {
            expanded.add(ROLE_DOCTOR);
        }
        return expanded;
    }

    /** The same rule as a Spring Security mapper, for the password-login path. */
    public static GrantedAuthoritiesMapper authoritiesMapper() {
        return (Collection<? extends GrantedAuthority> authorities) -> expand(
            authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .toList())
            .stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
    }
}
