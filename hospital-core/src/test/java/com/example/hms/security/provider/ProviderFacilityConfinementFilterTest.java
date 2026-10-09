package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The confinement filter and its policy over a real {@link RequestMappingHandlerMapping}
 * (a stand-in controller): who is confined (from the live context, no query),
 * the handler-level match, the unmapped-path refusal, the pass-through of a
 * wrong method or media type on an allowed path, and that the handler lookup
 * leaves the request as it found it.
 */
class ProviderFacilityConfinementFilterTest {

    private static final Set<FacilityType> PHARMACY = Set.of(FacilityType.PHARMACY);

    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
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

        @PostMapping(value = "/notifications/import", consumes = MediaType.APPLICATION_JSON_VALUE)
        String importNotifications() {
            return "imported";
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

    private static ProviderConfinementPolicy policy(RequestMappingHandlerMapping mapping,
                                                    HandlerExceptionResolver exceptionResolver) {
        return new ProviderConfinementPolicy(provider(mapping), provider(exceptionResolver));
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

    private static void actAs(Set<UUID> permitted, Set<FacilityType> providerTypes, Set<String> roles,
                              boolean superAdmin) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(permitted)
            .providerFacilityTypes(providerTypes)
            .assignedRoles(roles)
            .superAdmin(superAdmin)
            .build());
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        return request;
    }

    private static MockHttpServletRequest get(String path) {
        return request("GET", path);
    }

    @Test
    @DisplayName("an unpinned provider (pharmacy + hospital) is refused a hospital handler with the unmapped-path exception")
    void providerIsRefusedAsUnmapped() throws Exception {
        actAs(Set.of(pharmacyId, hospitalId), PHARMACY, Set.of("ROLE_PHARMACIST", "ROLE_PATIENT"), false);
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
        actAs(Set.of(pharmacyId), PHARMACY, Set.of("ROLE_PHARMACIST"), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), null)).doFilter(get("/patients/search"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("the match is the handler MVC dispatches to, not a prefix of the URI")
    void matchIsByHandlerNotPrefix() {
        ProviderConfinementPolicy policy = policy(mapping(), null);

        // /notifications/{id} is under the wholesale prefix: allowed.
        assertThat(policy.allows(get("/notifications/7"), PHARMACY, false)).isTrue();
        assertThat(policy.matchedHandlerPattern(get("/notifications/7"))).isEqualTo("/notifications/{id}");
        // /me/patient/profile is self-service, for a patient holder only.
        assertThat(policy.allows(get("/me/patient/profile"), PHARMACY, false)).isFalse();
        assertThat(policy.allows(get("/me/patient/profile"), PHARMACY, true)).isTrue();
        // A path MVC routes to a catch-all is that handler's, whatever it looks like.
        assertThat(policy.matchedHandlerPattern(get("/notifications/7/detail"))).isEqualTo("/{section}/{id}/detail");
        assertThat(policy.allows(get("/notifications/7/detail"), PHARMACY, true)).isFalse();
        // No handler at all: refused, the health probe aside.
        assertThat(policy.allows(get("/nothing/here/at/all"), PHARMACY, true)).isFalse();
        assertThat(policy.allows(get("/actuator/health"), PHARMACY, false)).isTrue();
    }

    @Test
    @DisplayName("a wrong method or media type on an ALLOWED path goes on to MVC (405, 415); on any other path it is refused")
    void partialMatchOnAnAllowedPathIsMvcs() {
        ProviderConfinementPolicy policy = policy(mapping(), null);

        // DELETE /notifications: mapped for GET only, and GET /notifications is allowed → MVC answers 405.
        assertThat(policy.allows(request("DELETE", "/notifications"), PHARMACY, false)).isTrue();
        // POST /notifications/import as text: the handler consumes JSON → MVC answers 415.
        MockHttpServletRequest textImport = request("POST", "/notifications/import");
        textImport.setContentType(MediaType.TEXT_PLAIN_VALUE);
        assertThat(policy.allows(textImport, PHARMACY, false)).isTrue();
        // DELETE /patients/search: mapped for GET only, and not allowed → the unmapped answer.
        assertThat(policy.allows(request("DELETE", "/patients/search"), PHARMACY, true)).isFalse();
    }

    @Test
    @DisplayName("the handler lookup leaves no request attribute behind and restores the ones it replaced")
    void lookupLeavesTheRequestClean() {
        ProviderConfinementPolicy policy = policy(mapping(), null);
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                get("/notifications/7"), request("DELETE", "/notifications")}) {
            request.setAttribute("pre-existing", "kept");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "earlier");

            policy.allows(request, PHARMACY, false);

            assertThat(Collections.list(request.getAttributeNames()))
                .containsExactlyInAnyOrder("pre-existing", HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            assertThat(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).isEqualTo("earlier");
        }
    }

    @Test
    @DisplayName("an allowed handler goes through")
    void allowedHandlerPasses() throws Exception {
        actAs(Set.of(pharmacyId), PHARMACY, Set.of("ROLE_PHARMACIST"), false);
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

        actAs(Set.of(hospitalId), Set.of(), Set.of("ROLE_DOCTOR"), false);
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        actAs(Set.of(pharmacyId), PHARMACY, Set.of("ROLE_SUPER_ADMIN"), true);
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        HospitalContextHolder.clear();
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        verify(chain, times(3)).doFilter(any(), any());
        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("without a policy bean (a @WebMvcTest slice) the filter passes everything through")
    void noPolicyPassesThrough() throws Exception {
        actAs(Set.of(pharmacyId), PHARMACY, Set.of("ROLE_PHARMACIST"), false);
        filter(null).doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("the confining types come from the live context; a PATIENT assignment makes a patient holder")
    void typesAndPatientHolderComeFromTheContext() {
        assertThat(ProviderConfinementPolicy.providerTypes(HospitalContext.builder()
            .providerFacilityTypes(PHARMACY).build())).containsExactly(FacilityType.PHARMACY);
        assertThat(ProviderConfinementPolicy.providerTypes(HospitalContext.builder()
            .providerFacilityTypes(PHARMACY).superAdmin(true).build())).isEmpty();
        assertThat(ProviderConfinementPolicy.providerTypes(null)).isEmpty();
        assertThat(ProviderConfinementPolicy.isPatientHolder(HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_PHARMACIST", "ROLE_PATIENT")).build())).isTrue();
        assertThat(ProviderConfinementPolicy.isPatientHolder(HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_PHARMACIST")).build())).isFalse();
        assertThat(ProviderConfinementPolicy.isPatientHolder(null)).isFalse();
    }
}
