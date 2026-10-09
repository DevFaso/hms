package com.example.hms.security.provider;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.HospitalUserDetails;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The caller's LIVE context from any principal, for the places that have no
 * context built by the HTTP context filters: STOMP frames, and the
 * confinement filter's fail-closed fallback when a principal arrives
 * authenticated without one (provider plan §3.3).
 *
 * <p>The context is the same computation the filters use
 * ({@link ActingScopeResolver#liveContext(UUID, String, java.util.List)} over
 * the live assignments), so the provider rule, the verified super-admin
 * exemption and the provider types are the same as on HTTP.
 *
 * <p>The local account is resolved as the Keycloak path resolves it: the
 * ws-ticket's user details, a token's {@code appUserId} claim, then the
 * principal name as a username, then as an email, then as a Keycloak subject.
 * A principal that resolves to no live account gets the unlinked context
 * (no assignments, so no provider types).
 */
@Component
public class ProviderCallerResolver {

    static final String CLAIM_APP_USER_ID = "appUserId";

    private final UserRepository userRepository;
    private final TenantRoleAssignmentAccessor assignmentAccessor;

    public ProviderCallerResolver(UserRepository userRepository, TenantRoleAssignmentAccessor assignmentAccessor) {
        this.userRepository = userRepository;
        this.assignmentAccessor = assignmentAccessor;
    }

    /** The principal's live context; the unlinked context when it names no live local account. */
    public HospitalContext liveContext(Principal principal) {
        String name = principal == null ? null : principal.getName();
        UUID localUserId = localUserId(principal);
        if (localUserId == null) {
            return ActingScopeResolver.unlinkedContext(name);
        }
        return ActingScopeResolver.liveContext(localUserId, name, assignmentAccessor.findAssignmentsForUser(localUserId));
    }

    /** The principal's live local account id, or {@code null}. */
    public UUID localUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof HospitalUserDetails details
            && details.getUserId() != null) {
            return details.getUserId();
        }
        if (principal instanceof JwtAuthenticationToken jwt) {
            UUID claimed = parseUuid(jwt.getToken().getClaimAsString(CLAIM_APP_USER_ID));
            if (claimed != null) {
                Optional<User> linked = userRepository.findById(claimed).filter(ProviderCallerResolver::live);
                if (linked.isPresent()) {
                    return linked.get().getId();
                }
            }
        }
        String name = principal.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(name)
            .or(() -> userRepository.findByEmail(name.toLowerCase(Locale.ROOT)))
            .or(() -> userRepository.findByKeycloakSubject(name))
            .filter(ProviderCallerResolver::live)
            .map(User::getId)
            .orElse(null);
    }

    private static boolean live(User user) {
        return user != null && user.getId() != null && !user.isDeleted();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
