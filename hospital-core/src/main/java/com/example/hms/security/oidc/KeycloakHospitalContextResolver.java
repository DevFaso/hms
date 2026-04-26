package com.example.hms.security.oidc;

import com.example.hms.security.context.HospitalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

/**
 * Translates Keycloak custom claims into a {@link HospitalContext} that the
 * existing tenant-scoped repositories ({@code TenantScopeSpecification},
 * {@code TenantContextAccessor}) and {@code @PreAuthorize} expressions can
 * consume identically to the legacy {@code JwtAuthenticationFilter} path.
 *
 * <p>The {@code hms-claims} client scope (see
 * {@code keycloak/realm-export.json}) emits two custom claims:
 * <ul>
 *   <li>{@code hospital_id} — a single UUID string identifying the
 *       caller's primary/active hospital.</li>
 *   <li>{@code role_assignments} — a list of {@code "<ROLE>@<hospital-uuid>"}
 *       strings describing every (role, hospital) pair the caller holds.</li>
 * </ul>
 *
 * <p>Defensive parsing: malformed entries (missing delimiter, non-UUID
 * fragments) are skipped with a debug log rather than failing the request.
 * Claim shape is owned by the realm protocol mappers and can drift; an
 * authenticated user with a malformed claim should still see the most
 * restrictive scope (empty), not a 500.</p>
 */
@Component
public class KeycloakHospitalContextResolver {

    private static final Logger log = LoggerFactory.getLogger(KeycloakHospitalContextResolver.class);

    static final String CLAIM_HOSPITAL_ID = "hospital_id";
    static final String CLAIM_ROLE_ASSIGNMENTS = "role_assignments";
    static final String CLAIM_PREFERRED_USERNAME = "preferred_username";

    private static final char ROLE_HOSPITAL_DELIMITER = '@';

    public HospitalContext resolve(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        UUID activeHospital = parseUuid(jwt.getClaimAsString(CLAIM_HOSPITAL_ID));
        Set<UUID> permittedHospitalIds = parseRoleAssignments(jwt.getClaim(CLAIM_ROLE_ASSIGNMENTS));
        if (activeHospital != null) {
            permittedHospitalIds.add(activeHospital);
        }

        boolean superAdmin = hasAuthority(authorities, ROLE_SUPER_ADMIN);
        boolean hospitalAdmin = hasAuthority(authorities, ROLE_HOSPITAL_ADMIN);

        return HospitalContext.builder()
                .principalUsername(jwt.getClaimAsString(CLAIM_PREFERRED_USERNAME))
                .activeHospitalId(activeHospital)
                .permittedHospitalIds(Set.copyOf(permittedHospitalIds))
                .superAdmin(superAdmin)
                .hospitalAdmin(hospitalAdmin)
                .build();
    }

    private Set<UUID> parseRoleAssignments(Object claim) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (!(claim instanceof Collection<?> entries)) {
            return ids;
        }
        for (Object entry : entries) {
            UUID hospitalId = hospitalIdFromAssignment(entry);
            if (hospitalId != null) {
                ids.add(hospitalId);
            }
        }
        return ids;
    }

    /**
     * Parse a single {@code "<ROLE>@<hospital-uuid>"} entry into the trailing
     * UUID, or return {@code null} when the entry is missing, malformed, or
     * carries a non-UUID hospital fragment.
     */
    private UUID hospitalIdFromAssignment(Object entry) {
        if (entry == null) {
            return null;
        }
        String value = entry.toString();
        int delim = value.indexOf(ROLE_HOSPITAL_DELIMITER);
        if (delim <= 0 || delim >= value.length() - 1) {
            log.debug("Ignoring malformed role_assignments entry: {}", value);
            return null;
        }
        return parseUuid(value.substring(delim + 1));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring non-UUID hospital identifier from JWT claim: {}", value);
            return null;
        }
    }

    private static boolean hasAuthority(Collection<? extends GrantedAuthority> authorities, String role) {
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority a : authorities) {
            if (a != null && role.equalsIgnoreCase(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
