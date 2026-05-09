package com.example.hms.security.oidc;

import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that mirrors the legacy
 * {@link com.example.hms.security.JwtAuthenticationFilter} contract for
 * Keycloak-issued JWTs: once OAuth2 resource-server has authenticated the
 * caller, this filter populates {@link HospitalContextHolder} from the
 * {@code hospital_id} / {@code role_assignments} claims so that all
 * downstream tenant-scoping (specifications, {@code @PreAuthorize},
 * SpEL queries) keeps working unchanged when
 * {@code app.auth.oidc.required=true}.
 *
 * <p>Wired in {@link com.example.hms.config.SecurityConfig} immediately
 * after Spring's {@code BearerTokenAuthenticationFilter} so the
 * {@code SecurityContextHolder} is already populated when this runs.
 * Registered only when {@code app.auth.oidc.issuer-uri} is set, matching
 * the rest of the OIDC stack.</p>
 *
 * <p>The filter clears {@link HospitalContextHolder} in {@code finally} so
 * the thread-local cannot leak across requests served by pooled threads.
 * It only clears state it set itself — for legacy bearers,
 * {@code JwtAuthenticationFilter} owns the lifecycle.</p>
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.auth.oidc.issuer-uri:}' != ''")
public class KeycloakHospitalContextFilter extends OncePerRequestFilter {

    private final KeycloakHospitalContextResolver resolver;
    private final IdleSessionGate idleSessionGate;

    public KeycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                         IdleSessionGate idleSessionGate) {
        this.resolver = resolver;
        this.idleSessionGate = idleSessionGate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean populated = false;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
                HospitalContext context = resolver.resolve(jwtAuth.getToken(), jwtAuth.getAuthorities());
                // Apply the same X-Hospital-Id header override the legacy
                // JwtAuthenticationFilter applies, so multi-hospital users
                // who pick an active hospital in the portal still see it
                // honoured under OIDC. Shared helper guarantees the two
                // filters cannot drift.
                context = HospitalContextRequestOverrides.applyRequestOverrides(context, request);
                HospitalContextHolder.setContext(context);
                populated = true;

                // ── Idle session timeout gate (v1.0 row 7) ────────────────
                // Mirrors the legacy JwtAuthenticationFilter wiring so
                // row 8's OIDC_REQUIRED=true cutover does not silently
                // disable the idle gate for OIDC clients.
                //
                // TODO(row 8 / Keycloak Phase C): the OIDC resolver does
                // not currently populate principalUserId — the realm
                // export emits hospital_id + role_assignments only. Until
                // a `user_id` (or equivalent) custom claim is mapped on
                // the realm, the gate short-circuits on null userId
                // (no-op, matching pre-row-7 behaviour). This wiring is
                // in place so the moment the resolver receives a userId,
                // enforcement turns on without further code changes.
                if (idleSessionGate.shouldReject(context.getPrincipalUserId(), jwtAuth.getAuthorities())) {
                    log.warn("[OIDC] Refusing request — user has been idle past the configured window");
                    HospitalContextHolder.clear();
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", IdleSessionGate.wwwAuthenticateChallenge());
                    return;
                }
                // Touch on the way through so the OIDC user's window resets.
                idleSessionGate.touchIfHuman(context.getPrincipalUserId(), jwtAuth.getAuthorities());
            }
            filterChain.doFilter(request, response);
        } finally {
            if (populated) {
                HospitalContextHolder.clear();
            }
        }
    }
}
