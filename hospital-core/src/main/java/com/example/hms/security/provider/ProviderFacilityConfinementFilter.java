package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Provider confinement (provider plan §3.3, §6.4, AC-8). A caller who is not a
 * verified super-admin and whose live permitted set contains a provider
 * facility (pinned to it or not) reaches only the handlers
 * {@link com.example.hms.security.provider.confinement.ProviderConfinement}
 * allows for that facility's type; anything else answers 404, exactly like an
 * unmapped path. Never flag-gated.
 *
 * <p>Runs inside the security chain after BOTH context filters (the legacy
 * {@code JwtAuthenticationFilter} and {@code KeycloakHospitalContextFilter},
 * which set the live context, the lifecycle gate and the {@code X-Hospital-Id}
 * refusal) and BEFORE {@code AuthorizationFilter}, so a confined caller never
 * sees a URL matcher's 403 for a hospital endpoint either.
 *
 * <p>DELIBERATELY NOT a {@code @Component}: {@code @WebMvcTest} slices scan
 * {@code Filter} beans, and a filter with a repository dependency breaks every
 * controller slice. {@code SecurityConfig} builds it around an
 * {@link ObjectProvider}; where no policy bean exists (a slice) it passes
 * every request through.
 */
public class ProviderFacilityConfinementFilter extends OncePerRequestFilter {

    private final ObjectProvider<ProviderConfinementPolicy> policyProvider;

    public ProviderFacilityConfinementFilter(ObjectProvider<ProviderConfinementPolicy> policyProvider) {
        this.policyProvider = policyProvider;
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
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        Set<FacilityType> providerTypes = policy.providerTypes(context);
        if (providerTypes.isEmpty()
            || policy.allows(request, providerTypes, ProviderConfinementPolicy.isPatientHolder(context))) {
            filterChain.doFilter(request, response);
            return;
        }
        policy.refuse(request, response);
    }
}
