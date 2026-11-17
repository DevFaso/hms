package com.example.hms.security.oidc;

import com.example.hms.config.SecurityConstants;
import com.example.hms.security.RoleExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

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
 * <p>Dropping them must not be silent: a realm where someone granted
 * {@code ROLE_DOCTOR} as a client role would otherwise just see its users
 * refused. When a client role looks like an HMS role (a {@code ROLE_*} name, or
 * a known HMS role code such as {@code doctor}), one WARN per token names the
 * client and the role codes, never the subject, username or email.
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

    private static final Logger log = LoggerFactory.getLogger(KeycloakJwtAuthenticationConverter.class);

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_EMAIL = "email";
    /** Token-supplied names are logged; anything outside this set is masked. */
    private static final Pattern UNSAFE_LOG_CHARS = Pattern.compile("[^A-Za-z0-9_.:-]");
    private static final int MAX_LOGGED_NAME_LENGTH = 64;

    /** Every {@code ROLE_*} constant in {@link SecurityConstants}, plus {@link RoleExpansion#ROLE_PHYSICIAN}. */
    private static final Set<String> KNOWN_HMS_ROLES = knownHmsRoles();

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
        warnOnIgnoredHmsClientRoles(jwt);
        List<String> normalised = rolesFromClaim(jwt.getClaim(CLAIM_REALM_ACCESS)).stream()
            .map(this::normaliseRoleName)
            .toList();
        return RoleExpansion.expand(normalised).stream()
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    }

    /**
     * One WARN when any client in {@code resource_access} carries a role that
     * looks like an HMS role; it grants nothing, so the realm is misconfigured.
     */
    private void warnOnIgnoredHmsClientRoles(Jwt jwt) {
        if (!(jwt.getClaim(CLAIM_RESOURCE_ACCESS) instanceof Map<?, ?> resourceMap)) {
            return;
        }
        Map<String, List<String>> ignored = new TreeMap<>();
        for (Map.Entry<?, ?> client : resourceMap.entrySet()) {
            List<String> hmsNamed = rolesFromClaim(client.getValue()).stream()
                .filter(KeycloakJwtAuthenticationConverter::looksLikeHmsRole)
                .map(role -> forLog(normaliseRoleName(role)))
                .distinct()
                .toList();
            if (!hmsNamed.isEmpty()) {
                ignored.put(forLog(String.valueOf(client.getKey())), hmsNamed);
            }
        }
        if (!ignored.isEmpty()) {
            log.warn("[OIDC] Ignoring HMS-named client roles {}: only realm roles (realm_access.roles) "
                + "grant authority, so grant these as realm roles", ignored);
        }
    }

    private static boolean looksLikeHmsRole(String role) {
        String upper = role.toUpperCase(Locale.ROOT);
        return upper.startsWith(ROLE_PREFIX) || KNOWN_HMS_ROLES.contains(ROLE_PREFIX + upper);
    }

    private static String forLog(String value) {
        String safe = UNSAFE_LOG_CHARS.matcher(value).replaceAll("?");
        return safe.length() > MAX_LOGGED_NAME_LENGTH ? safe.substring(0, MAX_LOGGED_NAME_LENGTH) : safe;
    }

    private static Set<String> knownHmsRoles() {
        Set<String> roles = new HashSet<>();
        for (Field field : SecurityConstants.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                && field.getName().startsWith(ROLE_PREFIX)) {
                try {
                    String value = (String) field.get(null);
                    if (value != null && value.startsWith(ROLE_PREFIX) && value.length() > ROLE_PREFIX.length()) {
                        roles.add(value);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("SecurityConstants." + field.getName() + " is not readable", e);
                }
            }
        }
        roles.add(RoleExpansion.ROLE_PHYSICIAN);
        return Set.copyOf(roles);
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
