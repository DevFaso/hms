package com.example.hms.security.oidc;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the {@link HospitalContext} of a Keycloak-authenticated request from
 * the SAME live computation the password path uses
 * ({@link ActingScopeResolver#liveContext}), keyed on the local user id
 * (docs/security/tenant-resolution.md §3.2).
 *
 * <p>The local user id comes <b>only</b> from the {@code appUserId} claim,
 * which {@code keycloak/realm-export.json} maps from the {@code app_user_id}
 * user attribute ({@code scripts/keycloak-migration} backfills it). There is
 * no fallback to {@code preferred_username}, email or {@code sub}: an identity
 * link must not rest on a username the local table cannot tell apart by case.
 * The claimed account must also be the one the token names — its username or
 * email equals the principal name — so a mis-set attribute cannot borrow
 * another account's hospitals. A token with no {@code appUserId}, or one
 * failing either check, gets the {@code NO_LOCAL_USER} context: no hospital,
 * not a super-admin.
 *
 * <p>The token's {@code hospital_id} and {@code role_assignments} claims are
 * no longer authorization inputs (Q5, option A): an assignment granted or
 * revoked in HMS counts on the next request instead of when the realm
 * attributes are next rewritten, and a SUPER_ADMIN realm role the assignment
 * table does not back grants no global view.
 */
@Component
public class KeycloakHospitalContextResolver {

    private static final Logger log = LoggerFactory.getLogger(KeycloakHospitalContextResolver.class);

    static final String CLAIM_APP_USER_ID = "appUserId";

    private final UserRepository userRepository;
    private final ActingScopeResolver actingScopeResolver;

    public KeycloakHospitalContextResolver(UserRepository userRepository, ActingScopeResolver actingScopeResolver) {
        this.userRepository = userRepository;
        this.actingScopeResolver = actingScopeResolver;
    }

    /**
     * @param jwt           the validated Keycloak token
     * @param principalName the name {@link KeycloakJwtAuthenticationConverter} resolved
     *                      ({@code preferred_username}, else email, else {@code sub})
     */
    public HospitalContext resolve(Jwt jwt, String principalName) {
        Optional<UUID> localUserId = linkedLocalUser(jwt, principalName);
        if (localUserId.isEmpty()) {
            return ActingScopeResolver.unlinkedContext(principalName);
        }
        return actingScopeResolver.liveContext(localUserId.get(), principalName);
    }

    private Optional<UUID> linkedLocalUser(Jwt jwt, String principalName) {
        UUID claimed = parseUuid(jwt.getClaimAsString(CLAIM_APP_USER_ID));
        if (claimed == null) {
            log.debug("[OIDC] Token carries no usable appUserId claim; no local account is linked");
            return Optional.empty();
        }
        Optional<User> user = userRepository.findById(claimed);
        if (user.isEmpty() || user.get().isDeleted() || !namesMatch(user.get(), principalName)) {
            // The claimed id and the principal name are not echoed: the id is
            // an account identifier and the name is PII.
            log.warn("[OIDC] appUserId claim does not match a live local account for this principal; "
                + "treating the request as unlinked");
            return Optional.empty();
        }
        return Optional.of(claimed);
    }

    private static boolean namesMatch(User user, String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return false;
        }
        String name = principalName.trim().toLowerCase(Locale.ROOT);
        return (user.getUsername() != null && user.getUsername().trim().toLowerCase(Locale.ROOT).equals(name))
            || (user.getEmail() != null && user.getEmail().trim().toLowerCase(Locale.ROOT).equals(name));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
