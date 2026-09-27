package com.example.hms.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way to turn an {@link Authentication} into the caller's local user
 * id (design D10: there were three, and one of them — {@code MeController}'s —
 * could not see Keycloak users at all).
 *
 * <ul>
 *   <li>Password path: the {@link HospitalUserDetails} principal's id.</li>
 *   <li>Keycloak path: the {@code appUserId} claim, which
 *       {@code keycloak/realm-export.json} maps from the {@code app_user_id}
 *       attribute, then the legacy {@code uid} / {@code userId} / {@code id}
 *       claims. The Keycloak {@code sub} is NOT a local id and is never
 *       read.</li>
 * </ul>
 *
 * <p>It lives in {@code security} so services stop importing the controller
 * helper {@code ControllerAuthUtils} for it, and it never throws — unlike
 * {@code AuthService.getCurrentUserId()}, which refuses every principal that
 * is not a {@link CustomUserDetails}.
 */
public final class PrincipalUserIds {

    /** Claim names that carry the local user id on a bearer token, in order. */
    static final List<String> USER_ID_CLAIMS = List.of("appUserId", "uid", "userId", "id");

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
            return ofJwt(token.getToken());
        }
        return Optional.empty();
    }

    private static Optional<UUID> ofJwt(Jwt jwt) {
        for (String claim : USER_ID_CLAIMS) {
            String raw = jwt.getClaimAsString(claim);
            if (raw != null && !raw.isBlank()) {
                try {
                    return Optional.of(UUID.fromString(raw.trim()));
                } catch (IllegalArgumentException ignored) {
                    // not a UUID — try the next claim
                }
            }
        }
        return Optional.empty();
    }
}
