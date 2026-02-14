package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.example.hms.config.SecurityConstants.HEADER_STRING;
import static com.example.hms.config.SecurityConstants.TOKEN_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    private static final Set<String> EXACT_SKIP_PATHS = Set.of(
        "/", "/index.html", "/favicon.ico",
        "/manifest.webmanifest", "/site.webmanifest",
        "/feature-flags"
    );

    private static final List<String> PREFIX_SKIP_PATHS = List.of(
        "/assets/", "/static/", "/dist/"
    );

    private static final int MAX_MISSING_PRINCIPAL_CACHE_SIZE = 2048;
    private static final long MISSING_PRINCIPAL_CACHE_TTL_MS = 30_000; // 30 seconds

    private final Set<String> missingPrincipalSubjects = ConcurrentHashMap.newKeySet();
    private final Deque<String> missingPrincipalOrder = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Long> missingPrincipalTimestamps = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        String path = request.getRequestURI();
        if (log.isTraceEnabled()) {
            log.trace("[JWT] Incoming path={} method={} authHeader={} thread={}", path, request.getMethod(),
                request.getHeader(HEADER_STRING), Thread.currentThread().getName());
        }

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                boolean continueChain = handleJwtAuthentication(request, response, path, jwt);
                if (!continueChain) {
                    return;
                }
            } else if (log.isTraceEnabled()) {
                log.trace("[JWT] No token on request path={}", path);
            }

            filterChain.doFilter(request, response);
        } catch (RuntimeException ex) {
            log.error("Could not set user authentication in security context", ex);
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            HospitalContextHolder.clear();
            if (log.isTraceEnabled()) {
                log.trace("[JWT] Completed filter for path={} in {}ms status={}", path, elapsed, response.getStatus());
            }
        }
    }

    private boolean handleJwtAuthentication(HttpServletRequest request, HttpServletResponse response,
                                            String path, String jwt) {
        String extractedSubject = safeExtractSubject(jwt);

        if (isKnownMissingPrincipal(extractedSubject)) {
            log.debug("[JWT] Short-circuiting cached missing user '{}' on path={}", extractedSubject, path);
            respondUnauthorized(response, extractedSubject);
            return false;
        }

        if (tokenProvider.validateToken(jwt)) {
            if (log.isTraceEnabled()) {
                log.trace("[JWT] Token present and valid (len={})", jwt.length());
            }
            boolean authenticated = applyAuthentication(jwt, request, extractedSubject);
            if (!authenticated) {
                respondUnauthorized(response, extractedSubject);
                return false;
            }
        } else {
            log.warn("[JWT] Token provided but invalid for path={} headerPrefixOk={} rawHeader={}", path,
                request.getHeader(HEADER_STRING) != null && request.getHeader(HEADER_STRING).startsWith(TOKEN_PREFIX),
                request.getHeader(HEADER_STRING));
        }
        return true;
    }

    private HospitalContext applyRequestOverrides(HospitalContext context, HttpServletRequest request) {
        HospitalContext effective = context != null ? context : HospitalContext.empty();
        String headerValue = request.getHeader("X-Hospital-Id");
        if (!StringUtils.hasText(headerValue)) {
            return effective;
        }

        try {
            UUID requestedHospital = UUID.fromString(headerValue.trim());

            boolean permitted = effective.isSuperAdmin()
                || effective.getPermittedHospitalIds().isEmpty()
                || effective.getPermittedHospitalIds().contains(requestedHospital);

            if (!permitted) {
                log.warn("[JWT] Ignoring X-Hospital-Id {} not in permitted scope {}",
                    requestedHospital, effective.getPermittedHospitalIds());
                return effective;
            }

            if (effective.getActiveHospitalId() == null
                || !requestedHospital.equals(effective.getActiveHospitalId())) {
                log.debug("[JWT] Overriding active hospital via header: {} (previously {})",
                    requestedHospital, effective.getActiveHospitalId());
            }

            return effective.toBuilder()
                .activeHospitalId(requestedHospital)
                .build();
        } catch (IllegalArgumentException ex) {
            log.warn("[JWT] Invalid X-Hospital-Id header value: {}", headerValue);
            return effective;
        }
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(HEADER_STRING);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(TOKEN_PREFIX)) {
            return bearerToken.substring(TOKEN_PREFIX.length());
        }
        return null;
    }

    private boolean applyAuthentication(String jwt, HttpServletRequest request, String extractedSubject) {
        try {
            Authentication authentication = tokenProvider.getAuthenticationFromJwt(jwt);
            log.debug("[JWT] Setting authentication for principal={} authorities={}",
                authentication.getName(), authentication.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);
            HospitalContext context = tokenProvider.extractHospitalContext(jwt, authentication);
            context = applyRequestOverrides(context, request);
            HospitalContextHolder.setContext(context);
            forgetMissingPrincipal(extractedSubject);
            return true;
        } catch (UsernameNotFoundException missingPrincipal) {
            handleMissingPrincipal(jwt, extractedSubject, missingPrincipal);
            SecurityContextHolder.clearContext();
            HospitalContextHolder.clear();
            return false;
        }
    }

    private void forgetMissingPrincipal(String subject) {
        if (!StringUtils.hasText(subject)) {
            return;
        }

        if (missingPrincipalSubjects.remove(subject)) {
            missingPrincipalOrder.remove(subject);
            missingPrincipalTimestamps.remove(subject);
            if (log.isDebugEnabled()) {
                log.debug("[JWT] Removed '{}' from missing-principal cache after successful authentication", subject);
            }
        }
    }

    private void handleMissingPrincipal(String jwt, String knownSubject, UsernameNotFoundException ex) {
        String subject = StringUtils.hasText(knownSubject) ? knownSubject : safeExtractSubject(jwt);
        if (!StringUtils.hasText(subject)) {
            log.debug("[JWT] Skipping token for missing principal (unable to extract subject): {}", ex.getMessage());
            return;
        }

        if (missingPrincipalSubjects.add(subject)) {
            rememberMissingPrincipal(subject);
            log.warn("[JWT] Ignoring token for missing user '{}' (suppressing future warnings)", subject);
        } else if (log.isTraceEnabled()) {
            log.trace("[JWT] Already warned about missing user '{}', suppressing log", subject);
        }
    }

    private void rememberMissingPrincipal(String subject) {
        missingPrincipalOrder.addLast(subject);
        missingPrincipalTimestamps.put(subject, System.currentTimeMillis());
        while (missingPrincipalOrder.size() > MAX_MISSING_PRINCIPAL_CACHE_SIZE) {
            String removed = missingPrincipalOrder.pollFirst();
            if (removed != null) {
                missingPrincipalSubjects.remove(removed);
                missingPrincipalTimestamps.remove(removed);
            }
        }
    }

    private String safeExtractSubject(String jwt) {
        if (!StringUtils.hasText(jwt)) {
            return null;
        }
        try {
            return tokenProvider.getUsernameFromJWT(jwt);
        } catch (RuntimeException extractionFailure) {
            if (log.isDebugEnabled()) {
                log.debug("[JWT] Unable to extract subject from token: {}", extractionFailure.getMessage());
            }
            return null;
        }
    }

    private boolean isKnownMissingPrincipal(String subject) {
        if (!StringUtils.hasText(subject) || !missingPrincipalSubjects.contains(subject)) {
            return false;
        }
        
        // Check if the cached entry has expired
        Long timestamp = missingPrincipalTimestamps.get(subject);
        if (timestamp != null && System.currentTimeMillis() - timestamp > MISSING_PRINCIPAL_CACHE_TTL_MS) {
            // Entry expired, remove from cache
            log.debug("[JWT] Missing principal cache entry expired for '{}'", subject);
            forgetMissingPrincipal(subject);
            return false;
        }
        
        return true;
    }

    private void respondUnauthorized(HttpServletResponse response, String subject) {
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"Hospital\"");
        }
        if (log.isTraceEnabled()) {
            log.trace("[JWT] Responded 401 for missing user '{}'", subject);
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!StringUtils.hasText(uri)) {
            return false;
        }

        if (EXACT_SKIP_PATHS.contains(uri)) {
            return true;
        }

        return PREFIX_SKIP_PATHS.stream().anyMatch(uri::startsWith);
    }
}
