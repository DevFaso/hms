package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
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
    private final TokenBlacklistService tokenBlacklistService;
    private final WsTicketService wsTicketService;
    private final HospitalUserDetailsService hospitalUserDetailsService;
    private final OrganizationLifecycleStatusService lifecycleStatusService;
    private final HospitalLifecycleStatusService hospitalLifecycleStatusService;
    private final GlobalSessionRevocationService globalSessionRevocationService;

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
            // ── WebSocket ticket-based auth (T-38) ──
            // Tickets are reusable within their TTL so SockJS can hit /info and the
            // transport upgrade with the same value (see WsTicketService Javadoc).
            if (path != null && path.startsWith("/api/ws-chat")
                    && handleWsTicketAuth(request, response, filterChain, path)) {
                return;
            }

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
            ImpersonationContextHolder.clear();
            if (log.isTraceEnabled()) {
                log.trace("[JWT] Completed filter for path={} in {}ms status={}", path, elapsed, response.getStatus());
            }
        }
    }

    /**
     * Handle WebSocket ticket-based authentication.
     * Returns {@code true} if the request was fully handled (authenticated or rejected),
     * or {@code false} if no ticket was present and normal JWT auth should proceed.
     */
    private boolean handleWsTicketAuth(HttpServletRequest request, HttpServletResponse response,
                                       FilterChain filterChain, String path) throws ServletException, IOException {
        String ticket = request.getParameter("ticket");
        if (!StringUtils.hasText(ticket)) {
            return false; // no ticket — fall through to JWT auth
        }
        String username = wsTicketService.redeem(ticket);
        if (username != null) {
            try {
                var userDetails = hospitalUserDetailsService.loadUserByUsername(username);
                if (!userDetails.isEnabled()) {
                    // Ticket-based handshake must respect the same email-verification
                    // gate as JWT auth. A redeemed ticket for an unverified or disabled
                    // account is not sufficient to attach role-based authorities.
                    // Username is redacted from the log line and from respondUnauthorized
                    // (defense-in-depth against log-aggregator misconfiguration / PII leak);
                    // the path + the ticket-issuance trail let ops correlate when needed.
                    log.warn("[WS-TICKET] Refusing ticket for a disabled/unverified account on path={}", path);
                    respondUnauthorized(response, null);
                    return true;
                }
                var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("[WS-TICKET] Authenticated user='{}' via ticket on path={}", username, path);
                filterChain.doFilter(request, response);
                return true;
            } catch (Exception ex) {
                log.warn("[WS-TICKET] Failed to load user for ticket, user='{}': {}", username, ex.getMessage());
            }
        }
        log.warn("[WS-TICKET] Invalid or expired ticket on path={}", path);
        respondUnauthorized(response, null);
        return true;
    }

    /**
     * MVP-7: returns {@code true} when the token's {@code iat} claim is
     * older than the global revocation timestamp. Tolerates tokens without
     * an iat claim (treats them as not-revoked) and exceptions during
     * extraction (treats them as not-revoked, so a parser hiccup never
     * locks every user out — the {@link #tokenProvider#validateToken}
     * call already guarded structural validity).
     */
    private boolean isRevokedByGlobalIat(String jwt) {
        Instant minIat = globalSessionRevocationService.getGlobalMinTokenIat();
        if (minIat == null || minIat.equals(Instant.EPOCH)) {
            return false;
        }
        try {
            Date issuedAt = tokenProvider.getIssuedAt(jwt);
            if (issuedAt == null) {
                return false;
            }
            return issuedAt.toInstant().isBefore(minIat);
        } catch (RuntimeException ex) {
            return false;
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
            return handleValidatedToken(request, response, path, jwt, extractedSubject);
        } else {
            // Do NOT log the raw Authorization header — even an invalid/expired token
            // still embeds the user's subject claim and could be a partial credential
            // leak through log aggregators. Log only the structural facts (was the
            // header present, did it start with "Bearer ", and the token length) so
            // ops can distinguish "no auth header" from "garbled header" from
            // "expired token" without ever shipping token bytes off the box.
            String rawHeader = request.getHeader(HEADER_STRING);
            boolean prefixOk = rawHeader != null && rawHeader.startsWith(TOKEN_PREFIX);
            int tokenLen = prefixOk ? rawHeader.length() - TOKEN_PREFIX.length() : 0;
            log.warn("[JWT] Token provided but invalid for path={} headerPresent={} headerPrefixOk={} tokenLen={}",
                path, rawHeader != null, prefixOk, tokenLen);
        }
        return true;
    }

    /**
     * Extracted from {@link #handleJwtAuthentication} (PR #228 SonarCloud
     * review — was driving cognitive complexity past the 15 threshold).
     * Runs the four post-validation gates in order: blacklist, global
     * revocation (MVP-7), authentication, and tenant lifecycle (MVP-2).
     * Each gate that rejects writes the unauthorized response and returns
     * false; on success the filter chain continues.
     */
    private boolean handleValidatedToken(
        HttpServletRequest request, HttpServletResponse response,
        String path, String jwt, String extractedSubject
    ) {
        // Reject tokens that have been explicitly revoked (logout / refresh rotation)
        String jti = tokenProvider.getJtiFromToken(jwt);
        if (jti != null && tokenBlacklistService.isBlacklisted(jti)) {
            log.debug("[JWT] Token jti={} is blacklisted, rejecting on path={}", jti, path);
            respondUnauthorized(response, extractedSubject);
            return false;
        }

        // ── Global session revocation gate (MVP-7) ─────────────────────
        // Force-logout-all bumps `globalMinTokenIat` to "now"; any token
        // issued before that instant is rejected. Cached + refreshed every
        // 30 s by GlobalSessionRevocationService so this stays a hot path.
        if (isRevokedByGlobalIat(jwt)) {
            log.warn("[JWT] Token rejected by global session revocation on path={}", path);
            respondUnauthorized(response, extractedSubject);
            return false;
        }

        if (log.isTraceEnabled()) {
            log.trace("[JWT] Token present and valid (len={})", jwt.length());
        }
        if (!applyAuthentication(jwt, request, extractedSubject)) {
            respondUnauthorized(response, extractedSubject);
            return false;
        }

        // ── Tenant lifecycle gate (MVP-2) ────────────────────────────────
        // After authentication has populated the HospitalContext, refuse
        // requests whose user is attached to an org in a non-active state
        // (SUSPENDED / ARCHIVED / PENDING_PURGE / PURGED). Super admins
        // bypass — they need cross-tenant access to manage these orgs.
        if (isBlockedByTenantLifecycle(HospitalContextHolder.getContextOrEmpty())) {
            log.warn("[JWT] Refusing request on path={} — user's organization is blocked by tenant lifecycle", path);
            SecurityContextHolder.clearContext();
            HospitalContextHolder.clear();
            ImpersonationContextHolder.clear();
            respondTenantBlocked(response);
            return false;
        }
        return true;
    }

    /**
     * Delegates to the shared
     * {@link HospitalContextRequestOverrides#applyRequestOverrides} so the
     * legacy bearer path and the OIDC path
     * ({@code KeycloakHospitalContextFilter}) honour the
     * {@code X-Hospital-Id} header identically. Behaviour is unchanged
     * vs. the inline implementation; the only difference is log-line
     * prefix ({@code [AUTH]} now, was {@code [JWT]}).
     */
    private HospitalContext applyRequestOverrides(HospitalContext context, HttpServletRequest request) {
        return HospitalContextRequestOverrides.applyRequestOverrides(context, request);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        // 1. Standard Authorization header
        String bearerToken = request.getHeader(HEADER_STRING);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(TOKEN_PREFIX)) {
            return bearerToken.substring(TOKEN_PREFIX.length());
        }
        // 2. WebSocket handshake paths now use single-use ticket auth (T-38)
        //    handled earlier in doFilterInternal. No JWT query-param fallback needed.
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
            tokenProvider.extractImpersonationContext(jwt)
                .ifPresent(ImpersonationContextHolder::set);
            forgetMissingPrincipal(extractedSubject);
            return true;
        } catch (UsernameNotFoundException missingPrincipal) {
            handleMissingPrincipal(jwt, extractedSubject, missingPrincipal);
            SecurityContextHolder.clearContext();
            HospitalContextHolder.clear();
            ImpersonationContextHolder.clear();
            return false;
        } catch (DisabledException disabled) {
            // The JWT is signed and unexpired but belongs to a user whose account is
            // disabled (most commonly: email not yet verified). Refuse the request rather
            // than letting the generic catch-all in doFilterInternal pass it through
            // unauthenticated.
            //
            // Subject + DisabledException.getMessage() are kept out of the log line
            // (the message is already generic; the subject is PII). Operators who
            // need to investigate can correlate via the JTI from the request log.
            log.warn("[JWT] Rejecting token for a disabled/unverified principal.");
            SecurityContextHolder.clearContext();
            HospitalContextHolder.clear();
            ImpersonationContextHolder.clear();
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
            // Subject is a username — kept out of the warn log (defense-in-depth
            // against PII bleed into log aggregators). At trace level a follow-up
            // line carries the subject for deep debugging in dev only.
            log.warn("[JWT] Ignoring token for a missing user (suppressing future warnings).");
            if (log.isTraceEnabled()) {
                log.trace("[JWT] First missing-user record subject='{}'", subject);
            }
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

    /**
     * Returns {@code true} when the authenticated user is attached to a
     * blocked organization OR a blocked hospital and is not a super admin.
     * Super admins always pass — they need cross-tenant access to manage
     * blocked tenants.
     *
     * <p>Hospital-level lifecycle (MVP-c batch) layers on top of the org
     * gate so a single hospital can be suspended without taking down the
     * whole organization, and the JWT filter enforces it at login time.
     */
    private boolean isBlockedByTenantLifecycle(HospitalContext context) {
        if (context.isSuperAdmin()) {
            return false;
        }
        return isBlockedByOrgLifecycle(context) || isBlockedByHospitalLifecycle(context);
    }

    private boolean isBlockedByOrgLifecycle(HospitalContext context) {
        Set<UUID> permitted = context.getPermittedOrganizationIds();
        UUID active = context.getActiveOrganizationId();
        if (permitted.isEmpty() && active == null) {
            return false;
        }
        Set<UUID> blocked = lifecycleStatusService.getBlockedOrganizationIds();
        return !blocked.isEmpty()
            && (containsAny(blocked, permitted) || (active != null && blocked.contains(active)));
    }

    private boolean isBlockedByHospitalLifecycle(HospitalContext context) {
        Set<UUID> permitted = context.getPermittedHospitalIds();
        UUID active = context.getActiveHospitalId();
        if (permitted.isEmpty() && active == null) {
            return false;
        }
        Set<UUID> blocked = hospitalLifecycleStatusService.getBlockedHospitalIds();
        return !blocked.isEmpty()
            && (containsAny(blocked, permitted) || (active != null && blocked.contains(active)));
    }

    private static boolean containsAny(Set<UUID> haystack, Set<UUID> needles) {
        for (UUID id : needles) {
            if (haystack.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 423 LOCKED with a clear, non-PII JSON body so the portal can surface
     *  an actionable message. The frontend interceptor only special-cases
     *  401/403 today, so a bare 423 with no body would render as a generic
     *  request failure with no explanation. */
    private void respondTenantBlocked(HttpServletResponse response) {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(423); // LOCKED — RFC 4918
        response.setHeader("X-Block-Reason", "tenant-lifecycle");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write(
                "{\"error\":\"tenant_blocked\","
                    + "\"message\":\"Access to this organization is currently unavailable due to its lifecycle status. "
                    + "Contact your super-admin if this is unexpected.\","
                    + "\"status\":423,"
                    + "\"blockReason\":\"tenant-lifecycle\"}"
            );
        } catch (IOException ex) {
            log.warn("[JWT] Failed to write 423 tenant-blocked response body", ex);
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
