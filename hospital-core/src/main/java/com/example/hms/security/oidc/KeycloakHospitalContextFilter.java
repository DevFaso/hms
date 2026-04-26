package com.example.hms.security.oidc;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
@Component
@ConditionalOnExpression("'${app.auth.oidc.issuer-uri:}' != ''")
public class KeycloakHospitalContextFilter extends OncePerRequestFilter {

    private final KeycloakHospitalContextResolver resolver;

    public KeycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver) {
        this.resolver = resolver;
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
                HospitalContextHolder.setContext(context);
                populated = true;
            }
            filterChain.doFilter(request, response);
        } finally {
            if (populated) {
                HospitalContextHolder.clear();
            }
        }
    }
}
