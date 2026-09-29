package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way to turn an {@link Authentication} into the caller's local user
 * id (design D10: there were three, and one of them — {@code MeController}'s —
 * could not see Keycloak users at all).
 *
 * <ul>
 *   <li>Password path: the {@link HospitalUserDetails} principal's id.</li>
 *   <li>Keycloak path: the id {@code KeycloakHospitalContextResolver} linked
 *       for this request — the {@code appUserId} claim, accepted only when
 *       that account carries the token's username or email. A claim is never
 *       read here directly: an ownership guard must not act as an account the
 *       scope resolver refused to link. The Keycloak {@code sub} is not a
 *       local id.</li>
 * </ul>
 *
 * <p>It lives in {@code security} so services stop importing the controller
 * helper {@code ControllerAuthUtils} for it, and it never throws.
 */
public final class PrincipalUserIds {

    private PrincipalUserIds() {
    }

    public static Optional<UUID> of(Authentication auth) {
        if (auth == null) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof HospitalUserDetails details) {
            return Optional.ofNullable(details.getUserId());
        }
        if (auth instanceof JwtAuthenticationToken token) {
            return linkedByTheFilter(token);
        }
        return Optional.empty();
    }

    /** The verified link of this request's context, when it was built for this token's principal. */
    private static Optional<UUID> linkedByTheFilter(JwtAuthenticationToken token) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.getPrincipalUserId() == null
            || !Objects.equals(context.getPrincipalUsername(), token.getName())) {
            return Optional.empty();
        }
        return Optional.of(context.getPrincipalUserId());
    }
}
