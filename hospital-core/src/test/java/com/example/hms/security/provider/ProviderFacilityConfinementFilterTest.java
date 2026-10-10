package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.ApiKeyAuthenticationFilter;
import com.example.hms.security.HospitalScopeResponses;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The confinement filter and its policy over a real {@link RequestMappingHandlerMapping}
 * (a stand-in controller): who is confined (from the live context), the
 * handler-level match, the own-id constraint, the unmapped-path refusal, the
 * pass-through of a wrong method or media type on an allowed path, the
 * fail-closed fallback when a principal arrives without a context, and that
 * the handler lookup leaves the request as it found it.
 */
class ProviderFacilityConfinementFilterTest {

    private static final Set<FacilityType> PHARMACY = Set.of(FacilityType.PHARMACY);

    private final UUID self = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final ProviderCallerResolver callerResolver = mock(ProviderCallerResolver.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private final ActingScopeResolver scopeResolver = scopeResolver(audit);

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

        @GetMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
        String user(@PathVariable String id) {
            return id;
        }

        @PutMapping("/users/{id}")
        String updateUser(@PathVariable String id) {
            return id;
        }
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private static ProviderConfinementPolicy policy(RequestMappingHandlerMapping mapping,
                                                    HandlerExceptionResolver exceptionResolver) {
        ProviderConfinementPolicy policy = new ProviderConfinementPolicy(provider(mapping), provider(exceptionResolver));
        policy.afterSingletonsInstantiated();
        return policy;
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
    private static ActingScopeResolver scopeResolver(CrossTenantReadAudit audit) {
        ObjectProvider<CrossTenantReadAudit> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        return new ActingScopeResolver(mock(TenantRoleAssignmentAccessor.class),
            mock(UserRoleHospitalAssignmentRepository.class), auditProvider);
    }

    @SuppressWarnings("unchecked")
    private ProviderFacilityConfinementFilter filter(ProviderConfinementPolicy policy) {
        ObjectProvider<ProviderConfinementPolicy> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(policy);
        ObjectProvider<ProviderCallerResolver> r = mock(ObjectProvider.class);
        when(r.getIfAvailable()).thenReturn(callerResolver);
        ObjectProvider<ActingScopeResolver> s = mock(ObjectProvider.class);
        when(s.getIfAvailable()).thenReturn(scopeResolver);
        return new ProviderFacilityConfinementFilter(p, r, s);
    }

    private HospitalContext context(Set<UUID> permitted, Set<FacilityType> providerTypes, Set<String> roles,
                                    boolean superAdmin) {
        return HospitalContext.builder()
            .principalUserId(self)
            .permittedHospitalIds(permitted)
            .providerFacilityTypes(providerTypes)
            .assignedRoles(roles)
            .superAdmin(superAdmin)
            .build();
    }

    private HospitalContext pharmacist(boolean patient) {
        return context(Set.of(pharmacyId), PHARMACY,
            patient ? Set.of("ROLE_PHARMACIST", "ROLE_PATIENT") : Set.of("ROLE_PHARMACIST"), false);
    }

    private void actAs(HospitalContext context) {
        HospitalContextHolder.setContext(context);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        return request;
    }

    private static MockHttpServletRequest get(String path) {
        return request("GET", path);
    }

    private static void authenticated(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_PHARMACIST")));
    }

    @Test
    @DisplayName("an unpinned provider (pharmacy + hospital) is refused a hospital handler with the unmapped-path exception")
    void providerIsRefusedAsUnmapped() throws Exception {
        actAs(context(Set.of(pharmacyId, hospitalId), PHARMACY, Set.of("ROLE_PHARMACIST", "ROLE_PATIENT"), false));
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
        actAs(pharmacist(false));
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
        assertThat(policy.allows(get("/notifications/7"), pharmacist(false))).isTrue();
        // /me/patient/profile is self-service, for a patient holder only.
        assertThat(policy.allows(get("/me/patient/profile"), pharmacist(false))).isFalse();
        assertThat(policy.allows(get("/me/patient/profile"), pharmacist(true))).isTrue();
        // A path MVC routes to a catch-all is that handler's, whatever it looks like.
        assertThat(policy.allows(get("/notifications/7/detail"), pharmacist(true))).isFalse();
        // No handler at all: refused, the health probe and the STOMP handshake aside.
        assertThat(policy.allows(get("/nothing/here/at/all"), pharmacist(true))).isFalse();
        assertThat(policy.allows(get("/actuator/health"), pharmacist(false))).isTrue();
        assertThat(policy.allows(get("/ws-chat/info"), pharmacist(false))).isTrue();
    }

    @Test
    @DisplayName("GET /users/{id} is the caller's OWN account only")
    void ownProfileOnly() {
        ProviderConfinementPolicy policy = policy(mapping(), null);

        assertThat(policy.allows(get("/users/" + self), pharmacist(false))).isTrue();
        assertThat(policy.allows(get("/users/" + self.toString().toUpperCase()), pharmacist(false))).isTrue();
        assertThat(policy.allows(get("/users/" + UUID.randomUUID()), pharmacist(false))).isFalse();
        assertThat(policy.allows(get("/users/" + self), null)).isFalse();
    }

    @Test
    @DisplayName("OPTIONS on a path an allowed handler serves goes on to MVC; on any other path it is refused")
    void optionsFollowsTheAllowedPaths() {
        ProviderConfinementPolicy policy = policy(mapping(), null);

        assertThat(policy.allows(request("OPTIONS", "/notifications"), pharmacist(false))).isTrue();
        assertThat(policy.allows(request("OPTIONS", "/notifications/7"), pharmacist(false))).isTrue();
        assertThat(policy.allows(request("OPTIONS", "/patients/search"), pharmacist(true))).isFalse();
        assertThat(policy.allows(request("OPTIONS", "/me/patient/profile"), pharmacist(false))).isFalse();
        assertThat(policy.allows(request("OPTIONS", "/me/patient/profile"), pharmacist(true))).isTrue();
    }

    @Test
    @DisplayName("a wrong method or media type on an ALLOWED path goes on to MVC (405, 415); on any other path it is refused")
    void partialMatchOnAnAllowedPathIsMvcs() {
        ProviderConfinementPolicy policy = policy(mapping(), null);

        // DELETE /notifications: mapped for GET only, and GET /notifications is allowed → MVC answers 405.
        assertThat(policy.allows(request("DELETE", "/notifications"), pharmacist(false))).isTrue();
        // POST /notifications/import as text: the handler consumes JSON → MVC answers 415.
        MockHttpServletRequest textImport = request("POST", "/notifications/import");
        textImport.setContentType(MediaType.TEXT_PLAIN_VALUE);
        assertThat(policy.allows(textImport, pharmacist(false))).isTrue();
        // DELETE /patients/search: mapped for GET only, and not allowed → the unmapped answer.
        assertThat(policy.allows(request("DELETE", "/patients/search"), pharmacist(true))).isFalse();
    }

    @Test
    @DisplayName("a 405 or OPTIONS whose Allow header would name a hidden handler, and a 406 on someone else's id, are unmapped")
    void partialMatchDisclosesNoHiddenHandler() {
        ProviderConfinementPolicy policy = policy(mapping(), null);
        UUID other = UUID.randomUUID();

        // /users/{id} maps GET (own id only) and PUT (not allowed): MVC's 405
        // and OPTIONS would list PUT in Allow, so neither goes on, own id or not.
        assertThat(policy.allows(request("PATCH", "/users/" + other), pharmacist(false))).isFalse();
        assertThat(policy.allows(request("PATCH", "/users/" + self), pharmacist(false))).isFalse();
        assertThat(policy.allows(request("OPTIONS", "/users/" + self), pharmacist(false))).isFalse();
        // GET /users/{id} produces JSON: a text/plain Accept is a 406 on the
        // caller's own id only; on another id it is the unmapped answer.
        MockHttpServletRequest ownAsText = get("/users/" + self);
        ownAsText.addHeader("Accept", MediaType.TEXT_PLAIN_VALUE);
        assertThat(policy.allows(ownAsText, pharmacist(false))).isTrue();
        MockHttpServletRequest otherAsText = get("/users/" + other);
        otherAsText.addHeader("Accept", MediaType.TEXT_PLAIN_VALUE);
        assertThat(policy.allows(otherAsText, pharmacist(false))).isFalse();
    }

    @Test
    @DisplayName("FALLBACK: a refused X-Hospital-Id is answered as both context filters answer it (audit row, holders cleared, 403)")
    void fallbackAnswersARefusedHeaderAsTheContextFiltersDo() throws Exception {
        UUID foreign = UUID.randomUUID();
        MockHttpServletResponse expected = new MockHttpServletResponse();
        HospitalScopeResponses.writeRefusal(expected, ActingScope.Reason.NOT_PERMITTED, foreign);

        for (HospitalContext live : List.of(pharmacist(false),
                context(Set.of(hospitalId), Set.of(), Set.of("ROLE_NURSE"), false))) {
            Authentication someone = user("someone");
            authenticated(someone);
            when(callerResolver.liveContext(someone)).thenReturn(live);
            MockHttpServletRequest request = get("/notifications");
            request.addHeader("X-Hospital-Id", foreign.toString());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter(policy(mapping(), null)).doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).isEqualTo(expected.getContentAsString());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(HospitalContextHolder.getContext()).isEmpty();
        }
        verify(chain, never()).doFilter(any(), any());
        verify(audit, times(2)).recordRefusal(self, null, foreign, ActingScope.Reason.NOT_PERMITTED,
            ActingScope.Source.HEADER);
    }

    @Test
    @DisplayName("the STOMP transport is recognised on the path Spring parses, not on a blind cut of the URI")
    void stompTransportIsReadOnTheParsedPath() throws Exception {
        Authentication pharmacist = user("pharm");
        authenticated(pharmacist);
        when(callerResolver.liveContext(pharmacist)).thenReturn(pharmacist(false));
        // The URI does not start with the context path: it names no path of
        // the application, so it is not the transport either.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/apx/ws-chat/123/abc/xhr_streaming");
        request.setContextPath("/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), null)).doFilter(request, response, chain);

        verify(callerResolver).liveContext(pharmacist);
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("the handler lookup leaves no request attribute behind and restores the ones it replaced")
    void lookupLeavesTheRequestClean() {
        ProviderConfinementPolicy policy = policy(mapping(), null);
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                get("/notifications/7"), request("DELETE", "/notifications")}) {
            request.setAttribute("pre-existing", "kept");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "earlier");

            policy.allows(request, pharmacist(false));

            assertThat(Collections.list(request.getAttributeNames()))
                .containsExactlyInAnyOrder("pre-existing", HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            assertThat(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).isEqualTo("earlier");
        }
    }

    @Test
    @DisplayName("an allowed handler goes through")
    void allowedHandlerPasses() throws Exception {
        actAs(pharmacist(false));
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

        actAs(context(Set.of(hospitalId), Set.of(), Set.of("ROLE_DOCTOR"), false));
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        actAs(context(Set.of(pharmacyId), PHARMACY, Set.of("ROLE_SUPER_ADMIN"), true));
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        HospitalContextHolder.clear();
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        verify(chain, times(3)).doFilter(any(), any());
        verifyNoInteractions(resolver, callerResolver);
    }

    @Test
    @DisplayName("FAIL CLOSED: an authenticated principal with no context built gets its live context computed; a provider is confined")
    void authenticatedWithoutContextIsStillConfined() throws Exception {
        Authentication pharmacist = user("pharm");
        authenticated(pharmacist);
        when(callerResolver.liveContext(pharmacist)).thenReturn(pharmacist(false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), null)).doFilter(get("/patients/search"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("FAIL CLOSED: when that live context cannot be computed, the request is refused")
    void unavailableContextIsRefused() throws Exception {
        Authentication someone = user("nurse");
        authenticated(someone);
        when(callerResolver.liveContext(someone)).thenThrow(new IllegalStateException("assignments unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(policy(mapping(), null)).doFilter(get("/notifications"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("FALLBACK: a database failure computing the context is a retryable 503, not the refusal's 404")
    void databaseFailureIsRetryable() throws Exception {
        Authentication someone = user("nurse");
        authenticated(someone);
        MockHttpServletResponse response = new MockHttpServletResponse();

        doThrow(new DataAccessResourceFailureException("db down")).when(callerResolver).liveContext(someone);
        filter(policy(mapping(), null)).doFilter(get("/notifications"), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);

        MockHttpServletResponse noTransaction = new MockHttpServletResponse();
        doThrow(new CannotCreateTransactionException("no connection")).when(callerResolver).liveContext(someone);
        filter(policy(mapping(), null)).doFilter(get("/notifications"), noTransaction, chain);
        assertThat(noTransaction.getStatus()).isEqualTo(503);

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("without a context: a hospital user, an anonymous request and a partner API key pass")
    void contextlessNonProvidersPass() throws Exception {
        ProviderFacilityConfinementFilter filter = filter(policy(mapping(), null));

        Authentication nurse = user("nurse");
        authenticated(nurse);
        when(callerResolver.liveContext(nurse)).thenReturn(context(Set.of(hospitalId), Set.of(), Set.of("ROLE_NURSE"), false));
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        authenticated(new AnonymousAuthenticationToken("key", "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        authenticated(new UsernamePasswordAuthenticationToken("partner-key", null,
            List.of(new SimpleGrantedAuthority(ApiKeyAuthenticationFilter.ROLE_PARTNER_API))));
        filter.doFilter(get("/patients/search"), new MockHttpServletResponse(), chain);

        verify(chain, times(3)).doFilter(any(), any());
        verify(callerResolver, times(1)).liveContext(any());
    }

    @Test
    @DisplayName("the STOMP transport is never a fallback computation, for anyone")
    void stompTransportSkipsTheFallback() throws Exception {
        authenticated(user("nurse"));

        filter(policy(mapping(), null)).doFilter(get("/ws-chat/123/abc/xhr_streaming"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(callerResolver);
    }

    @Test
    @DisplayName("a computed confining context is kept for the rest of the request, then cleared")
    void computedContextIsKeptForTheRequest() throws Exception {
        Authentication pharmacist = user("pharm");
        authenticated(pharmacist);
        when(callerResolver.liveContext(pharmacist)).thenReturn(pharmacist(false));
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        FilterChain downstream = (request, response) -> seen.set(HospitalContextHolder.getContext().orElse(null));

        filter(policy(mapping(), null)).doFilter(get("/notifications"), new MockHttpServletResponse(), downstream);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getPrincipalUserId()).isEqualTo(self);
        assertThat(seen.get().getProviderFacilityTypes()).containsExactly(FacilityType.PHARMACY);
        assertThat(HospitalContextHolder.getContext()).isEmpty();
        verify(callerResolver, times(1)).liveContext(pharmacist);
    }

    @Test
    @DisplayName("a computed context that does not confine is not kept: the request goes on as it would have")
    void nonConfiningContextIsNotKept() throws Exception {
        Authentication nurse = user("nurse");
        authenticated(nurse);
        when(callerResolver.liveContext(nurse)).thenReturn(context(Set.of(hospitalId), Set.of(), Set.of("ROLE_NURSE"), false));
        AtomicReference<Optional<HospitalContext>> seen =
            new AtomicReference<>();
        FilterChain downstream = (request, response) -> seen.set(HospitalContextHolder.getContext());

        filter(policy(mapping(), null)).doFilter(get("/patients/search"), new MockHttpServletResponse(), downstream);

        assertThat(seen.get()).isEmpty();
    }

    @Test
    @DisplayName("without a policy bean (a @WebMvcTest slice) the filter passes everything through")
    void noPolicyPassesThrough() throws Exception {
        actAs(pharmacist(false));
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
