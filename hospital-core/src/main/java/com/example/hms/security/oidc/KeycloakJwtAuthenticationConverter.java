package com.example.hms.security.oidc;

import com.example.hms.security.RoleExpansion;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps Keycloak JWT claims to Spring Security {@code GrantedAuthority}s.
 *
 * <p>Only {@code realm_access.roles} is read. Every HMS role is a realm role:
 * {@code keycloak/realm-export.json} defines the {@code ROLE_*} set under
 * {@code roles.realm} and no client role at all, the {@code hms-claims} scope
 * maps {@code realm_access.roles}, and the KC-4 migration
 * ({@code scripts/keycloak-migration}) assigns users realm roles only.
 * {@code resource_access.<client>.roles} is deliberately ignored: it carries
 * the roles of EVERY client in the realm that the user holds one on
 * (Keycloak's own {@code account} client puts {@code manage-account} and
 * {@code view-profile} in every token), so reading it let a role named
 * {@code doctor} or {@code super_admin} on any unrelated client become a
 * platform authority.
 *
 * <p>Roles are upper-cased and prefixed with {@code ROLE_} when not already
 * prefixed, so a realm role may be named {@code ROLE_DOCTOR} or {@code doctor},
 * and then widened by {@link RoleExpansion#expand}, the rule the password path
 * ({@code JwtTokenProvider.getAuthenticationFromJwt}) applies: the same role
 * list yields the same authority set on both paths.
 *
 * <p>Keycloak's stock realm roles ({@code offline_access},
 * {@code uma_authorization}, {@code default-roles-<realm>}) pass through as
 * {@code ROLE_OFFLINE_ACCESS} and the like, as they always have. No guard
 * names them.
 *
 * <p>This converter is always available as a Spring bean. It is only wired into the
 * resource-server filter chain when {@code app.auth.oidc.issuer-uri} is non-empty
 * (see {@link OidcResourceServerConfig}).</p>
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_EMAIL = "email";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        String principalName = resolvePrincipalName(jwt);
        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    /**
     * The realm roles, normalised to {@code ROLE_*} and expanded by
     * {@link RoleExpansion}; de-duplicated, in claim order.
     */
    Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<String> normalised = rolesFromClaim(jwt.getClaim(CLAIM_REALM_ACCESS)).stream()
            .map(this::normaliseRoleName)
            .toList();
        return RoleExpansion.expand(normalised).stream()
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    }

    private List<String> rolesFromClaim(Object claim) {
        if (!(claim instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object roles = map.get(CLAIM_ROLES);
        if (!(roles instanceof Collection<?> roleCollection)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(roleCollection.size());
        for (Object r : roleCollection) {
            if (r != null) {
                String s = r.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    String normaliseRoleName(String role) {
        Objects.requireNonNull(role, "role");
        String upper = role.toUpperCase(Locale.ROOT);
        return upper.startsWith(ROLE_PREFIX) ? upper : ROLE_PREFIX + upper;
    }

    /**
     * Prefer {@code preferred_username}, then {@code email}, then {@code sub} so
     * downstream code (which expects {@code Authentication.getName()} to look like
     * a username/email) keeps working under OIDC.
     */
    String resolvePrincipalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString(CLAIM_PREFERRED_USERNAME);
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        String email = jwt.getClaimAsString(CLAIM_EMAIL);
        if (email != null && !email.isBlank()) {
            return email;
        }
        return jwt.getSubject();
    }
}
