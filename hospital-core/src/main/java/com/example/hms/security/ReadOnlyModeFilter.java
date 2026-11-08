package com.example.hms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>QA D1404: a POST that only reads goes through: the banner promises that
 * viewing works, and {@code POST /billing-invoices/search} emptied the billing
 * page. The handler declares it ({@link ReadsOnly}), and this filter asks Spring's
 * own mapping which handler a request reaches, so {@code /x/search} and a write
 * at {@code /x/{id}} cannot be mistaken for each other. Anything else stays
 * refused, an unknown path and a failed lookup included. The HAPI FHIR servlet
 * has no MVC handler: its two read forms are named in {@link #isFhirRead}.
 *
 * <p>User decision 2026-11-06: {@code /api/auth/} is no longer open as a prefix. A write under it
 * passes only when {@link #OPEN_AUTH_WRITES} names it, exactly: an account action that a downtime
 * can wait for (registration, a new username or e-mail, recovery contacts, an optional MFA change)
 * is refused like any write, and a new route is refused until {@code ReadOnlyAuthWritesGuardTest}
 * and this list classify it. The filter runs before authentication, so the two enrolment routes pass
 * here and {@code MfaController} refuses them unless the enrolment is what blocks the sign-in.
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
     * Writes that must survive downtime: the portal's error-telemetry sink and
     * the toggle endpoint itself — a mode you cannot turn off is an outage, not a mode.
     */
    private static final List<String> ALLOWLISTED_PREFIXES = List.of(
        "/api/frontend-audit",
        "/api/super-admin/downtime"
    );

    /**
     * The writes under {@code /api/auth/} that survive a downtime, as exact paths: signing in and out
     * (refresh writes go to Redis, not the app DB), resetting a forgotten password, the first sign-in
     * steps that block all reading, and what keeps an open session readable (the lock screen, the
     * notification socket). Everything else under the prefix is refused.
     */
    public static final Set<String> OPEN_AUTH_WRITES = Set.of(
        "/api/auth/login",
        "/api/auth/logout",
        "/api/auth/token/refresh",
        "/api/auth/mfa/verify",
        "/api/auth/mfa/enroll",
        "/api/auth/mfa/verify-enrollment",
        "/api/auth/request-reset",
        "/api/auth/reset-password",
        "/api/auth/password/request",
        "/api/auth/password/request-reset",
        "/api/auth/password/confirm",
        "/api/auth/password/reset-password",
        "/api/auth/username/reminder",
        "/api/auth/me/complete-setup",
        "/api/auth/me/change-password",
        "/api/auth/verify-email",
        "/api/auth/resend-verification",
        "/api/auth/verify-password",
        "/api/auth/ws-ticket"
    );

    private static final String FHIR_PREFIX = "/api/fhir/";
    private static final String POST = "POST";

    /** QA D1407: the default sentence, in the reader's language. */
    public static final String DEFAULT_MESSAGE_KEY = "downtime.readOnly.default";
    private static final String DEFAULT_MESSAGE_FALLBACK =
        "The system is in read-only mode for maintenance. Viewing is available; changes are temporarily disabled.";

    private final DowntimeStateService downtimeStateService;
    private final ObjectProvider<HandlerMapping> handlerMappingProvider;
    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        DowntimeStateService.DowntimeSnapshot snapshot = downtimeStateService.snapshot();
        if (!snapshot.readOnly() || isDeclaredRead(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("[DOWNTIME] 503 for {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("X-Readonly-Mode", "true");
        response.setHeader("Retry-After", "300");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // customMessage: an administrator wrote it, in their own language. Otherwise
        // the portal's banner keeps its own translated sentence.
        boolean custom = snapshot.message() != null;
        String message = custom ? snapshot.message() : defaultMessage(request);
        response.getWriter().write("{\"error\":\"READ_ONLY_MODE\",\"message\":\""
            + jsonEscape(message) + "\",\"customMessage\":" + custom + "}");
    }

    private String defaultMessage(HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        return messageSource.getMessage(DEFAULT_MESSAGE_KEY, null, DEFAULT_MESSAGE_FALLBACK, locale);
    }

    /** The two characters that end or break a JSON string, and the control characters a typed message may hold. */
    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                out.append('\'');
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c < ' ') {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private boolean isDeclaredRead(HttpServletRequest request) {
        if (!POST.equals(request.getMethod())) {
            return false;
        }
        return isFhirRead(request.getRequestURI()) || handlerReadsOnly(request);
    }

    /** {@code POST [type]/_search} and {@code $everything}: the FHIR forms of a read. {@code $export} starts a job. */
    static boolean isFhirRead(String uri) {
        return uri.startsWith(FHIR_PREFIX) && (uri.endsWith("/_search") || uri.endsWith("/$everything"));
    }

    private boolean handlerReadsOnly(HttpServletRequest request) {
        HandlerMapping mapping = handlerMappingProvider.getIfAvailable();
        if (mapping == null) {
            return false;
        }
        // Its own attributes: the lookup stamps the request, and the dispatcher
        // must meet the request as if nobody had looked.
        HttpServletRequest lookup = new IsolatedAttributesRequest(request);
        try {
            ServletRequestPathUtils.parseAndCache(lookup);
            HandlerExecutionChain chain = mapping.getHandler(lookup);
            return chain != null
                && chain.getHandler() instanceof HandlerMethod handler
                && handler.hasMethodAnnotation(ReadsOnly.class);
        } catch (Exception ex) {
            // No handler for it, a verb or media type it does not take: not a declared read.
            log.debug("[DOWNTIME] no read handler for {}: {}", request.getRequestURI(), ex.getMessage());
            return false;
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return OPEN_AUTH_WRITES.contains(path) || ALLOWLISTED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static final class IsolatedAttributesRequest extends HttpServletRequestWrapper {
        private final Map<String, Object> attributes = new HashMap<>();

        IsolatedAttributesRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.containsKey(name) ? attributes.get(name) : super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.put(name, null);
        }
    }
}
