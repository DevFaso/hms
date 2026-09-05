package com.example.hms.security;

import com.example.hms.service.apikey.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates third-party clients on the {@code /partner/**} surface by
 * the {@code X-API-Key} header (Tier 2 item 45).
 *
 * <p>Deliberately NOT a {@code @Component} and holding its dependency via
 * {@link ObjectProvider}: {@code @WebMvcTest} slices scan Filter
 * components and would demand the service bean in every controller slice
 * (the PR #536 lesson — 257 failures). Constructed inside SecurityConfig;
 * in a slice with no {@link ApiKeyService} bean it degrades to a no-op.
 *
 * <p>A valid key yields {@code ROLE_PARTNER_API} and nothing else — a
 * partner credential is an identity for the partner surface, never a
 * staff role. An absent or bad key sets no authentication; the
 * authorization layer then refuses {@code /partner/**} with the standard
 * 401, and the caller learns nothing about WHY the key was refused.
 */
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ROLE_PARTNER_API = "ROLE_PARTNER_API";

    private static final String PARTNER_PATH_PREFIX = "/partner";

    private final ObjectProvider<ApiKeyService> apiKeyServiceProvider;

    public ApiKeyAuthenticationFilter(ObjectProvider<ApiKeyService> apiKeyServiceProvider) {
        this.apiKeyServiceProvider = apiKeyServiceProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals(PARTNER_PATH_PREFIX) || path.startsWith(PARTNER_PATH_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ApiKeyService apiKeyService = apiKeyServiceProvider.getIfAvailable();
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (apiKeyService != null && rawKey != null && !rawKey.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            apiKeyService.authenticate(rawKey).ifPresent(auth -> {
                PreAuthenticatedAuthenticationToken token =
                    new PreAuthenticatedAuthenticationToken(
                        auth, null, List.of(new SimpleGrantedAuthority(ROLE_PARTNER_API)));
                token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(token);
                log.debug("Partner API request authenticated for key {}", auth.keyId());
            });
        }
        filterChain.doFilter(request, response);
    }
}
