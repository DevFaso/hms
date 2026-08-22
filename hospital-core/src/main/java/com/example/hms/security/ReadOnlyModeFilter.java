package com.example.hms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Rejects mutating requests while downtime read-only mode is active
 * (P3 #23a), modeled on {@link RateLimitFilter}. Deliberately an HTTP-layer
 * gate rather than DB-level read-only: login writes User.lastLoginAt, and
 * the audit logger swallows persistence failures — a DB-level window would
 * break authentication and silently drop the compliance trail.
 *
 * <p>The 503 carries {@code X-Readonly-Mode: true} as a DISCRIMINATOR:
 * the portal's offline-dispense interceptor treats a bare 503 on
 * POST /pharmacy/dispense as transient and silently queues the write for
 * replay — the header is what lets it (and the global error surface) tell
 * "system is read-only" from "server hiccup".
 *
 * <p>IMPORTANT: servlet-registered filters see the {@code /api} context
 * prefix in {@code getRequestURI()} — allowlist entries are written WITH
 * the prefix (the RateLimitFilter convention), unlike SecurityConfig
 * matchers which are context-stripped. Copying SecurityConfig-style paths
 * here would silently never match.
 *
 * <p>DELIBERATELY NOT {@code @Component}: {@code @WebMvcTest} slices scan
 * {@code Filter} components, and a filter with a service dependency breaks
 * every controller slice in the suite (JwtAuthenticationFilter has to be
 * {@code @MockitoBean}-ed everywhere for exactly this reason). Registered
 * via {@link com.example.hms.config.ReadOnlyModeFilterConfig} instead,
 * which slice tests never scan.
 */
@Slf4j
@RequiredArgsConstructor
public class ReadOnlyModeFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * Writes that must survive downtime: authentication (login/refresh/MFA
     * are POSTs; refresh writes go to Redis, not the app DB), the portal's
     * error-telemetry sink, and the toggle endpoint itself — a mode you
     * cannot turn off is an outage, not a mode.
     */
    private static final List<String> ALLOWLISTED_PREFIXES = List.of(
        "/api/auth/",
        "/api/frontend-audit",
        "/api/super-admin/downtime"
    );

    private final DowntimeStateService downtimeStateService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        DowntimeStateService.DowntimeSnapshot snapshot = downtimeStateService.snapshot();
        if (!snapshot.readOnly()) {
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("[DOWNTIME] 503 for {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("X-Readonly-Mode", "true");
        response.setHeader("Retry-After", "300");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String message = snapshot.message() != null
            ? snapshot.message()
            : "The system is in read-only mode for maintenance. Viewing is available; changes are temporarily disabled.";
        response.getWriter().write("{\"error\":\"READ_ONLY_MODE\",\"message\":\""
            + message.replace("\"", "'") + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return ALLOWLISTED_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
