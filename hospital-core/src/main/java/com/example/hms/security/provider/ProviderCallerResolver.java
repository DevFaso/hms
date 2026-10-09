package com.example.hms.security.provider;

import com.example.hms.security.HospitalUserDetails;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;

/**
 * The caller's LIVE context from a principal, for the places that have no
 * context built by the HTTP context filters: STOMP frames, and the
 * confinement filter's fail-closed fallback when a principal arrives
 * authenticated without one (provider plan §3.3).
 *
 * <p>The local account is linked exactly as the HTTP context filters link it,
 * never more loosely:
 * <ul>
 *   <li>the ws-ticket's (or the legacy token's) {@link HospitalUserDetails}: its user id;</li>
 *   <li>a Keycloak token: {@link KeycloakHospitalContextResolver}, the same
 *       {@code appUserId} rules (a live account whose name matches the
 *       principal) the Keycloak filter applies;</li>
 *   <li>anything else: unlinked ({@code NO_LOCAL_USER}). No username, email or
 *       subject guessing: HTTP links no account that way either.</li>
 * </ul>
 * The context is the same live computation as on HTTP, so the provider types
 * and the verified super-admin exemption are the same.
 */
@Component
public class ProviderCallerResolver {

    private final TenantRoleAssignmentAccessor assignmentAccessor;
    private final ObjectProvider<KeycloakHospitalContextResolver> keycloakResolverProvider;

    public ProviderCallerResolver(TenantRoleAssignmentAccessor assignmentAccessor,
                                  ObjectProvider<KeycloakHospitalContextResolver> keycloakResolverProvider) {
        this.assignmentAccessor = assignmentAccessor;
        this.keycloakResolverProvider = keycloakResolverProvider;
    }

    /**
     * The principal's live context; the unlinked context (no local user, no
     * assignments) when the principal links no local account.
     */
    public HospitalContext liveContext(Principal principal) {
        String name = principal == null ? null : principal.getName();
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof HospitalUserDetails details
            && details.getUserId() != null) {
            return ActingScopeResolver.liveContext(details.getUserId(), name,
                assignmentAccessor.findAssignmentsForUser(details.getUserId()));
        }
        if (principal instanceof JwtAuthenticationToken jwt) {
            KeycloakHospitalContextResolver keycloak = keycloakResolverProvider.getIfAvailable();
            if (keycloak != null) {
                return keycloak.resolve(jwt.getToken(), jwt.getName());
            }
        }
        return ActingScopeResolver.unlinkedContext(name);
    }

    /** The principal's linked local account id, or {@code null} (the context's own principal id). */
    public static UUID linkedUserId(HospitalContext context) {
        return context == null ? null : context.getPrincipalUserId();
    }
}
