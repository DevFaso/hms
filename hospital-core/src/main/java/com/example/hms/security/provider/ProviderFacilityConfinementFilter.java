package com.example.hms.security.provider;

import com.example.hms.security.ApiKeyAuthenticationFilter;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.provider.confinement.CommonProviderConfinement;
import com.example.hms.security.provider.confinement.ProviderConfinement;
import com.example.hms.security.tenant.ActingScopeResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Provider confinement (provider plan §3.3, §6.4, AC-8). A caller who is not a
 * verified super-admin and whose live assignments include a provider facility
 * (pinned to it or not) reaches only the handlers
 * {@link com.example.hms.security.provider.confinement.ProviderConfinement}
 * allows for that facility's type; anything else answers 404, exactly like an
 * unmapped path. Never flag-gated.
 *
 * <p><b>MFA (AC-13).</b> Before the allow-list, a confined caller whose
 * authentication carries no second factor ({@link ProviderMfaGate}) reaches
 * only the sign-in and MFA enrolment handlers under {@code /auth}; every other
 * request answers 403 {@code mfa.enrollment.required}, the same answer for an
 * allowed, a refused and an unmapped path, so it discloses nothing. A hospital
 * user and a verified super-admin are never confined, so never asked.
 *
 * <p>Runs inside the security chain after BOTH context filters (the legacy
 * {@code JwtAuthenticationFilter} and {@code KeycloakHospitalContextFilter},
 * which set the live context, the lifecycle gate and the {@code X-Hospital-Id}
 * refusal) and BEFORE {@code AuthorizationFilter}, so a confined caller never
 * sees a URL matcher's 403 for a hospital endpoint either.
 *
 * <p><b>Fail closed.</b> An authenticated principal that arrives with no
 * context built (the ws-ticket handshake, or a context filter that failed and
 * let the request through) is not taken as unconfined: its live context is
 * computed here, by the same computation, from the principal, and its
 * {@code X-Hospital-Id} is applied and, when refused, answered (audit row and
 * 403) by {@link ActingScopeResolver#answerRefusedHeader}, the one code path
 * both context filters use. If that cannot be done, the request is refused. Only a partner API key, which is no user,
 * and an anonymous request skip it. A principal naming no local account has no
 * assignment, so no provider types.
 *
 * <p>DELIBERATELY NOT a {@code @Component}: {@code @WebMvcTest} slices scan
 * {@code Filter} beans, and a filter with a repository dependency breaks every
 * controller slice. {@code SecurityConfig} builds it around
 * {@link ObjectProvider}s; where no policy bean exists (a slice) it passes
 * every request through.
 */
@Slf4j
public class ProviderFacilityConfinementFilter extends OncePerRequestFilter {

    private final ObjectProvider<ProviderConfinementPolicy> policyProvider;
    private final ObjectProvider<ProviderCallerResolver> callerResolverProvider;
    private final ObjectProvider<ActingScopeResolver> scopeResolverProvider;
    private final ObjectProvider<ProviderMfaGate> mfaGateProvider;

    public ProviderFacilityConfinementFilter(ObjectProvider<ProviderConfinementPolicy> policyProvider,
                                             ObjectProvider<ProviderCallerResolver> callerResolverProvider,
                                             ObjectProvider<ActingScopeResolver> scopeResolverProvider,
                                             ObjectProvider<ProviderMfaGate> mfaGateProvider) {
        this.policyProvider = policyProvider;
        this.callerResolverProvider = callerResolverProvider;
        this.scopeResolverProvider = scopeResolverProvider;
        this.mfaGateProvider = mfaGateProvider;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        ProviderConfinementPolicy policy = policyProvider.getIfAvailable();
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<HospitalContext> built = HospitalContextHolder.getContext();
        if (built.isPresent()) {
            decide(policy, built.get(), request, response, filterChain);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isUserPrincipal(authentication) || isStompHandshake(request)) {
            // No user, or the SockJS/STOMP transport: allowed for everyone
            // (non-MVC), its frames confined by the STOMP interceptor. No
            // fallback computation on every transport request.
            filterChain.doFilter(request, response);
            return;
        }
        HospitalContext live;
        try {
            live = fallbackLive(policy, authentication, request, response);
        } catch (DataAccessException | CannotCreateTransactionException unavailable) {
            // The database, not the caller, anywhere on this path (the
            // context, the header's classification, the refusal's audit row):
            // a retryable 503, never the "not found" a refusal answers.
            log.warn("[CONFINEMENT] Live context unavailable ({}); answered 503",
                unavailable.getClass().getSimpleName());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            return;
        }
        if (live == null) {
            return;
        }
        if (!ProviderConfinementPolicy.isConfined(live)) {
            // Not confined: links no local account, or holds no provider type.
            // Nothing is kept, so the request goes on exactly as it would have
            // (code that reads the holder directly treats "no context" as it
            // always has; ensureContext computes it on first use).
            decide(policy, live, request, response, filterChain);
            return;
        }
        // A confined caller: kept for the rest of this request, so nothing
        // downstream computes it again (ensureContext reads the holder first)
        // and every reader sees the confined context. Cleared when the request
        // leaves this filter.
        HospitalContextHolder.setContext(live);
        try {
            decide(policy, live, request, response, filterChain);
        } finally {
            HospitalContextHolder.clear();
        }
    }

    private void decide(ProviderConfinementPolicy policy, HospitalContext context, HttpServletRequest request,
                        HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!ProviderConfinementPolicy.isConfined(context)) {
            filterChain.doFilter(request, response);
            return;
        }
        // AC-13 first: without a second factor, only sign-in and MFA enrolment.
        ProviderMfaGate mfaGate = mfaGateProvider.getIfAvailable();
        if (mfaGate != null
            && mfaGate.refuses(context, SecurityContextHolder.getContext().getAuthentication())
            && !policy.exemptFromSecondFactor(request)) {
            mfaGate.refuse(request, response);
            return;
        }
        if (policy.allows(request, context)) {
            filterChain.doFilter(request, response);
            return;
        }
        policy.refuse(request, response);
    }

    /** The SockJS/STOMP transport ({@code /ws-chat/**}), on the path within the application. */
    private static boolean isStompHandshake(HttpServletRequest request) {
        return ProviderConfinement.underAny(ProviderConfinementPolicy.pathWithinApplication(request),
            CommonProviderConfinement.NON_MVC_PREFIXES);
    }

    /**
     * The fallback's live context with {@code X-Hospital-Id} applied, or
     * {@code null} once the request has been answered: refused (the context
     * cannot be computed) or a refused header (audited, 403, by the same code
     * as both context filters). A database failure propagates to the caller.
     */
    private HospitalContext fallbackLive(ProviderConfinementPolicy policy, Authentication authentication,
                                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        ActingScopeResolver scopeResolver = scopeResolverProvider.getIfAvailable();
        HospitalContext context = scopeResolver == null ? null : fallbackContext(authentication);
        if (context == null) {
            policy.refuse(request, response);
            return null;
        }
        HospitalContext live = scopeResolver.withHeader(context, request);
        if (scopeResolver.answerRefusedHeader(live, response)) {
            return null;
        }
        return live;
    }

    /** The live context of a principal that arrived without one, or {@code null} when it cannot be computed. */
    private HospitalContext fallbackContext(Authentication authentication) {
        ProviderCallerResolver resolver = callerResolverProvider.getIfAvailable();
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.liveContext(authentication);
        } catch (DataAccessException | CannotCreateTransactionException databaseDown) {
            throw databaseDown;
        } catch (RuntimeException unavailable) {
            log.warn("[CONFINEMENT] Live context unavailable for an authenticated request ({}); refused",
                unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /** An authenticated user principal: not anonymous, not a partner API key. */
    private static boolean isUserPrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ApiKeyAuthenticationFilter.ROLE_PARTNER_API.equals(authority.getAuthority())) {
                return false;
            }
        }
        return true;
    }
}
