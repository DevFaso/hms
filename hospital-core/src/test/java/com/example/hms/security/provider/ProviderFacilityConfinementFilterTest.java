package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The confinement filter and its policy over a real {@link RequestMappingHandlerMapping}
 * (two stand-in controllers): who is confined, the handler-level match, the
 * unmapped-path refusal, and that the handler lookup leaves the request as it
 * found it.
 */
class ProviderFacilityConfinementFilterTest {

    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final HospitalRepository hospitalRepository = mock(HospitalRepository.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final FilterChain chain = mock(FilterChain.class);

    @RestController
    static class StandInController {
        @GetMapping("/notifications")
        String notifications() {
            return "ok";
        }

        @GetMapping("/notifications/{id}")
        String notification(@PathVariable String id) {
            return id;
        }

        @GetMapping("/{section}/{id}/detail")
        String catchAll(@PathVariable String section, @PathVariable String id) {
            return section + id;
        }

        @GetMapping("/patients/search")
        String search() {
            return "patients";
        }

        @GetMapping("/me/patient/profile")
        String profile() {
            return "me";
        }
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private ProviderConfinementPolicy policy(RequestMappingHandlerMapping mapping, HandlerExceptionResolver exceptionResolver) {
        return new ProviderConfinementPolicy(hospitalRepository, provider(mapping), provider(exceptionResolver));
    }

    private static RequestMappingHandlerMapping mapping() {
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.registerSingleton("standIn", StandInController.class);
        context.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        return mapping;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RequestMappingHandlerMapping> provider(RequestMappingHandlerMapping value) {
        ObjectProvider<RequestMappingHandlerMapping> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HandlerExceptionResolver> provider(HandlerExceptionResolver value) {
        ObjectProvider<HandlerExceptionResolver> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ProviderFacilityConfinementFilter filter(ProviderConfinementPolicy policy) {
        ObjectProvider<ProviderConfinementPolicy> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(policy);
        return new ProviderFacilityConfinementFilter(p);
    }

    private void actAs(Set<UUID> permitted, Set<String> roles, boolean superAdmin) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(permitted)
            .assignedRoles(roles)
            .superAdmin(superAdmin)
            .build());
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api" + path);
        request.setContextPath("/api");
        return request;
    }

    @Test
    @DisplayName("an unpinned provider (pharmacy + hospital) is refused a hospital handler with the unmapped-path exception")
    void providerIsRefusedAsUnmapped() throws Exception {
        actAs(Set.of(pharmacyId, hospitalId), Set.of("ROLE_PHARMACIST", "ROLE_PATIENT"), false);
        when(hospitalRepository.findProviderFacilityTypesByIdIn(any())).thenReturn(List.of(FacilityType.PHARMACY));
        when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());
        MockHttpServletRequest request = get("/patients/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), resolver)).doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(resolver).resolveException(any(), any(), isNull(), any(NoResourceFoundException.class));
    }

    @Test
    @DisplayName("without an exception resolver the refusal is still a bare 404")
    void refusalFallsBackToNotFound() throws Exception {
        actAs(Set.of(pharmacyId), Set.of("ROLE_PHARMACIST"), false);
        when(hospitalRepository.findProviderFacilityTypesByIdIn(any())).thenReturn(List.of(FacilityType.PHARMACY));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), null)).doFilter(get("/patients/search"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("the match is the handler MVC dispatches to, not a prefix of the URI")
    void matchIsByHandlerNotPrefix() throws Exception {
        actAs(Set.of(pharmacyId), Set.of("ROLE_PHARMACIST"), false);
        when(hospitalRepository.findProviderFacilityTypesByIdIn(any())).thenReturn(List.of(FacilityType.PHARMACY));
        ProviderConfinementPolicy policy = policy(mapping(), null);

        // /notifications/{id} is under the wholesale prefix: allowed.
        assertThat(policy.allows(get("/notifications/7"), Set.of(FacilityType.PHARMACY), false)).isTrue();
        assertThat(policy.matchedHandlerPattern(get("/notifications/7"))).isEqualTo("/notifications/{id}");
        // /me/patient/profile is self-service, for a patient holder only.
        assertThat(policy.allows(get("/me/patient/profile"), Set.of(FacilityType.PHARMACY), false)).isFalse();
        assertThat(policy.allows(get("/me/patient/profile"), Set.of(FacilityType.PHARMACY), true)).isTrue();
        // A path MVC routes to a catch-all is that handler's, whatever it looks like.
        assertThat(policy.matchedHandlerPattern(get("/notifications/7/detail"))).isEqualTo("/{section}/{id}/detail");
        assertThat(policy.allows(get("/notifications/7/detail"), Set.of(FacilityType.PHARMACY), true)).isFalse();
        // No handler at all: refused, the health probe aside.
        assertThat(policy.allows(get("/nothing/here/at/all"), Set.of(FacilityType.PHARMACY), true)).isFalse();
        assertThat(policy.allows(get("/actuator/health"), Set.of(FacilityType.PHARMACY), false)).isTrue();
        // A wrong method on an allowed path is no handler either.
        MockHttpServletRequest delete = get("/notifications");
        delete.setMethod("DELETE");
        assertThat(policy.allows(delete, Set.of(FacilityType.PHARMACY), false)).isFalse();
    }

    @Test
    @DisplayName("the handler lookup leaves no request attribute behind and restores the ones it replaced")
    void lookupLeavesTheRequestClean() {
        ProviderConfinementPolicy policy = policy(mapping(), null);
        MockHttpServletRequest request = get("/notifications/7");
        request.setAttribute("pre-existing", "kept");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "earlier");

        assertThat(policy.matchedHandlerPattern(request)).isEqualTo("/notifications/{id}");

        assertThat(java.util.Collections.list(request.getAttributeNames()))
            .containsExactlyInAnyOrder("pre-existing", HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        assertThat(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).isEqualTo("earlier");
    }

    @Test
    @DisplayName("an allowed handler goes through")
    void allowedHandlerPasses() throws Exception {
        actAs(Set.of(pharmacyId), Set.of("ROLE_PHARMACIST"), false);
        when(hospitalRepository.findProviderFacilityTypesByIdIn(any())).thenReturn(List.of(FacilityType.PHARMACY));
        MockHttpServletRequest request = get("/notifications");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), resolver)).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("a hospital user, a super-admin and an anonymous request are not confined")
    void nonProvidersPass() throws Exception {
        ProviderFacilityConfinementFilter filter = filter(policy(mapping(), resolver));

        actAs(Set.of(hospitalId), Set.of("ROLE_DOCTOR"), false);
        when(hospitalRepository.findProviderFacilityTypesByIdIn(any())).thenReturn(List.of());
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        actAs(Set.of(pharmacyId), Set.of("ROLE_SUPER_ADMIN"), true);
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        HospitalContextHolder.clear();
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        verify(chain, org.mockito.Mockito.times(3)).doFilter(any(), any());
        verifyNoInteractions(resolver);
        // Only the hospital user's permitted set was looked up: a super-admin
        // and an anonymous caller cost no query.
        verify(hospitalRepository, org.mockito.Mockito.times(1)).findProviderFacilityTypesByIdIn(any());
    }

    @Test
    @DisplayName("without a policy bean (a @WebMvcTest slice) the filter passes everything through")
    void noPolicyPassesThrough() throws Exception {
        actAs(Set.of(pharmacyId), Set.of("ROLE_PHARMACIST"), false);
        filter(null).doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("a PATIENT assignment makes a patient holder, wherever it is bound")
    void patientHolder() {
        assertThat(ProviderConfinementPolicy.isPatientHolder(HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_PHARMACIST", "ROLE_PATIENT")).build())).isTrue();
        assertThat(ProviderConfinementPolicy.isPatientHolder(HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_PHARMACIST")).build())).isFalse();
        assertThat(ProviderConfinementPolicy.isPatientHolder(null)).isFalse();
    }
}
