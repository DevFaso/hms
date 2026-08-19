package com.example.hms.security.oidc;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps Keycloak JWT claims to Spring Security {@code GrantedAuthority}s.
 *
 * <p>S-03 phase 1: this converter is the bridge between Keycloak realm/client roles
 * (claim layout: {@code realm_access.roles} and {@code resource_access.<client>.roles})
 * and the existing {@code ROLE_*} authority strings used throughout the app
 * ({@code SecurityConstants}). Roles are normalised to upper-case and prefixed with
 * {@code ROLE_} when the claim value isn't already prefixed, so Keycloak admins can
 * configure roles either as {@code ROLE_DOCTOR} or simply {@code DOCTOR}.</p>
 *
 * <p>This converter is always available as a Spring bean. It is only wired into the
 * resource-server filter chain when {@code app.auth.oidc.issuer-uri} is non-empty
 * (see {@link OidcResourceServerConfig}).</p>
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";
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
     * Collect realm + client roles from the Keycloak token and turn each into a
     * {@code ROLE_*} {@link SimpleGrantedAuthority}, de-duplicated.
     */
    Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<String> roleNames = new HashSet<>();
        roleNames.addAll(extractRealmRoles(jwt));
        roleNames.addAll(extractAllClientRoles(jwt));

        Collection<GrantedAuthority> authorities = new ArrayList<>(roleNames.size());
        for (String role : roleNames) {
            authorities.add(new SimpleGrantedAuthority(normaliseRoleName(role)));
        }
        return authorities;
    }

    private List<String> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim(CLAIM_REALM_ACCESS);
        return rolesFromClaim(realmAccess);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAllClientRoles(Jwt jwt) {
        Object resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);
        if (!(resourceAccess instanceof Map<?, ?> resourceMap)) {
            return List.of();
        }
        List<String> all = new ArrayList<>();
        for (Object clientEntry : resourceMap.values()) {
            all.addAll(rolesFromClaim(clientEntry));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
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
        String upper = role.toUpperCase();
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
