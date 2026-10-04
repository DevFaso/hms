package com.example.hms.security.oidc;

import com.example.hms.repository.UserRepository;
import com.example.hms.security.HospitalScopeResponses;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.SuperAdminAuthorities;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
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
 * caller, this filter populates {@link HospitalContextHolder} from the SAME
 * live computation the password path uses ({@link KeycloakHospitalContextResolver}
 * → {@link ActingScopeResolver#liveContext}), then applies the same gates in
 * the same order: local account, idle window, tenant lifecycle (423 — until
 * this filter enforced it a Keycloak user at a suspended hospital kept
 * working, D14), and a refused {@code X-Hospital-Id} (403).
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
    private final ActingScopeResolver actingScopeResolver;
    private final IdleSessionGate idleSessionGate;
    private final TenantLifecycleGate tenantLifecycleGate;
    private final UserRepository userRepository;

    public KeycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                         ActingScopeResolver actingScopeResolver,
                                         IdleSessionGate idleSessionGate,
                                         TenantLifecycleGate tenantLifecycleGate,
                                         UserRepository userRepository) {
        this.resolver = resolver;
        this.actingScopeResolver = actingScopeResolver;
        this.idleSessionGate = idleSessionGate;
        this.tenantLifecycleGate = tenantLifecycleGate;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean populated = false;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
                // ── Local verification gate (option A, 2026-09-02) ────────
                // A Keycloak token proves the IdP authenticated the caller,
                // but verification state lives on the LOCAL row: accounts
                // start inactive until the emailed code is entered, and the
                // legacy path enforces that via CustomUserDetails.isEnabled().
                // Until Phase C mirrors enable/disable into the realm, an
                // inactive or soft-deleted local account must be just as
                // unusable under OIDC. No local row means a Keycloak-only
                // identity (pre-provisioning) — allowed, as before, but with
                // no hospital scope (NO_LOCAL_USER).
                if (localAccountBlocked(jwtAuth.getName())) {
                    log.warn("[OIDC] Refusing request — local account is inactive or deleted");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                HospitalContext context = resolver.resolve(jwtAuth.getToken(), jwtAuth.getName());
                context = actingScopeResolver.withHeader(context, request);
                // Q10, option A: a SUPER_ADMIN realm role the assignment table
                // does not back grants no authority either.
                SecurityContextHolder.getContext().setAuthentication(SuperAdminAuthorities.reconcile(jwtAuth, context));
                HospitalContextHolder.setContext(context);
                populated = true;

                // ── Idle session timeout gate (v1.0 row 7) ────────────────
                // principalUserId is the linked local account (appUserId), so
                // the gate enforces here exactly as on the password path; an
                // unlinked principal has none and the gate short-circuits.
                if (idleSessionGate.shouldReject(context.getPrincipalUserId(), jwtAuth.getAuthorities())) {
                    log.warn("[OIDC] Refusing request — user has been idle past the configured window");
                    HospitalContextHolder.clear();
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", IdleSessionGate.WWW_AUTHENTICATE_CHALLENGE);
                    return;
                }

                // ── Tenant lifecycle gate (MVP-2), same semantics as the password path ──
                if (tenantLifecycleGate.isBlocked(context)) {
                    log.warn("[OIDC] Refusing request — user's organization is blocked by tenant lifecycle");
                    HospitalContextHolder.clear();
                    SecurityContextHolder.clearContext();
                    HospitalScopeResponses.writeTenantBlocked(response);
                    return;
                }

                // ── Refused X-Hospital-Id (design Q3, option A) ───────────
                if (ActingScopeResolver.isRefusedHeader(context)) {
                    actingScopeResolver.auditRefusedHeader(context);
                    HospitalContextHolder.clear();
                    SecurityContextHolder.clearContext();
                    HospitalScopeResponses.writeRefusal(response, context.getScopeRefusal(), context.getRefusedHospitalId());
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

    /**
     * The principal name is {@code preferred_username}, falling back to
     * {@code email} then {@code sub} (see KeycloakJwtAuthenticationConverter),
     * so the lookup mirrors that order. One extra query per OIDC request —
     * the same price the legacy path already pays in
     * {@code CustomUserDetailsService.loadUserByUsername}.
     */
    private boolean localAccountBlocked(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return false;
        }
        return userRepository.findByUsernameIgnoreCase(principalName)
            .or(() -> userRepository.findByEmail(principalName.toLowerCase(java.util.Locale.ROOT)))
            .map(user -> !user.isActive() || user.isDeleted())
            .orElse(false);
    }
}
