package com.example.hms.security.tenant;

import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HospitalIdNarrowingInterceptor}: a handler's {@code hospitalId} path
 * variable or request parameter narrows the request's scope before the handler
 * runs (design §3.4 step 2), and a hospital the caller may not act at is
 * refused and audited.
 */
class HospitalIdNarrowingInterceptorTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private HospitalIdNarrowingInterceptor interceptor;

    /** Stand-in handlers: the shapes the interceptor must recognise. */
    static class Handlers {
        @GetMapping("/h/{hospitalId}/x")
        public void byPath(@PathVariable UUID hospitalId) {
            // shape only: the interceptor reads the signature, never calls it
        }

        @GetMapping("/x")
        public void byParam(@RequestParam(required = false) UUID hospitalId) {
            // shape only: the interceptor reads the signature, never calls it
        }

        @GetMapping("/h/{id}")
        public void byNamedPath(@PathVariable("hospitalId") UUID id) {
            // shape only: the interceptor reads the signature, never calls it
        }

        @GetMapping("/book/{hospitalId}")
        @HospitalScopeExempt(reason = "the hospital is a booking target")
        public void exempt(@PathVariable UUID hospitalId) {
            // shape only: the interceptor reads the signature, never calls it
        }

        @GetMapping("/y")
        public void noHospital(@RequestParam UUID patientId) {
            // shape only: the interceptor reads the signature, never calls it
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CrossTenantReadAudit> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        ActingScopeResolver resolver = ActingScopeTestSupport.resolver(assignments, auditProvider);
        ObjectProvider<ActingScopeResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        interceptor = new HospitalIdNarrowingInterceptor(provider);
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a held hospital in the path narrows the scope to it, before the handler")
    void pathVariableNarrows() throws Exception {
        holdsAAndB();
        assertThat(interceptor.preHandle(pathRequest(B), new MockHttpServletResponse(), handler("byPath"))).isTrue();
        assertThat(ActingScopeResolver.scopeOf(HospitalContextHolder.getContextOrEmpty()))
            .isEqualTo(new ActingScope.Pinned(B, ActingScope.Source.REQUESTED));
    }

    @Test
    @DisplayName("a held hospital in ?hospitalId= narrows, and a path variable named by its annotation does too")
    void requestParamAndNamedPathNarrow() throws Exception {
        holdsAAndB();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setParameter("hospitalId", B.toString());
        interceptor.preHandle(request, new MockHttpServletResponse(), handler("byParam"));
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(B);

        holdsAAndB();
        interceptor.preHandle(pathRequest(B), new MockHttpServletResponse(), handler("byNamedPath"));
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(B);
    }

    @Test
    @DisplayName("a super-admin naming a hospital is pinned to it: filtered and audited as that hospital")
    void superAdminIsPinned() throws Exception {
        ActingScopeTestSupport.globalSuperAdmin(USER);
        interceptor.preHandle(pathRequest(B), new MockHttpServletResponse(), handler("byPath"));
        assertThat(HospitalContextHolder.getContextOrEmpty().isGlobalView()).isFalse();
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(B);
    }

    @Test
    @DisplayName("an unheld hospital is refused 403 with its reason, and audited")
    void unheldHospitalIsRefused() throws Exception {
        ActingScopeTestSupport.actingAt(USER, A);
        when(assignments.existsByUserIdAndHospitalIdAndActiveFalse(USER, B)).thenReturn(true);

        MockHttpServletRequest request = pathRequest(B);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod byPath = handler("byPath");
        assertThatThrownBy(() -> interceptor.preHandle(request, response, byPath))
            .isInstanceOf(HospitalScopeRefusedException.class)
            .extracting(e -> ((HospitalScopeRefusedException) e).getReason())
            .isEqualTo("NO_LONGER_PERMITTED");
        verify(audit).recordRefusal(USER, null, B, ActingScope.Reason.NO_LONGER_PERMITTED, ActingScope.Source.REQUESTED);
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).as("unchanged").isEqualTo(A);
    }

    @Test
    @DisplayName("left alone: an exempt handler, a request with no tenant context, a non-UUID value, no hospitalId")
    void leftAlone() throws Exception {
        ActingScopeTestSupport.actingAt(USER, A);
        interceptor.preHandle(pathRequest(B), new MockHttpServletResponse(), handler("exempt"));
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(A);

        MockHttpServletRequest garbage = new MockHttpServletRequest("GET", "/h/x/x");
        garbage.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("hospitalId", "not-a-uuid"));
        interceptor.preHandle(garbage, new MockHttpServletResponse(), handler("byPath"));
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(A);

        MockHttpServletRequest noParam = new MockHttpServletRequest("GET", "/y");
        noParam.setParameter("hospitalId", B.toString());
        interceptor.preHandle(noParam, new MockHttpServletResponse(), handler("noHospital"));
        assertThat(HospitalContextHolder.getContextOrEmpty().pinnedHospitalId()).isEqualTo(A);

        HospitalContextHolder.clear();
        assertThat(interceptor.preHandle(pathRequest(B), new MockHttpServletResponse(), handler("byPath"))).isTrue();
        assertThat(HospitalContextHolder.getContext()).as("no context is not invented").isEmpty();
    }

    private static void holdsAAndB() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER)
            .permittedHospitalIds(Set.of(A, B))
            .activeHospitalId(A)
            .headerOverridden(true)
            .build());
    }

    private static MockHttpServletRequest pathRequest(UUID hospitalId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/h/" + hospitalId + "/x");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("hospitalId", hospitalId.toString()));
        return request;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        for (var method : Handlers.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return new HandlerMethod(new Handlers(), method);
            }
        }
        throw new NoSuchMethodException(name);
    }
}
