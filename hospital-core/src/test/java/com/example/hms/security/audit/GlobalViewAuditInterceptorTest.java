package com.example.hms.security.audit;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalViewAuditInterceptor}: one row per request a super-admin served
 * in global view, from the FINAL scope, only when the scope was read (design
 * §3.6 item 2).
 */
class GlobalViewAuditInterceptorTest {

    private static final UUID ADMIN = UUID.randomUUID();

    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private GlobalViewAuditInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CrossTenantReadAudit> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        interceptor = new GlobalViewAuditInterceptor(provider);
        request = new MockHttpServletRequest("GET", "/api/patients");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/patients");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a global-view request that read the scope writes one row, with the route pattern")
    void globalViewReadIsRecorded() {
        ActingScopeTestSupport.globalSuperAdmin(ADMIN);
        HospitalContextHolder.seal();

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(audit).recordGlobalViewRequest(ADMIN, null, "GET", "/patients");
    }

    @Test
    @DisplayName("nothing for a request that never read the scope, a pinned super-admin, staff, or a failure")
    void everythingElseIsNot() {
        ActingScopeTestSupport.globalSuperAdmin(ADMIN);
        interceptor.afterCompletion(request, response, new Object(), null); // never sealed

        ActingScopeTestSupport.superAdminAt(ADMIN, UUID.randomUUID());
        HospitalContextHolder.seal();
        interceptor.afterCompletion(request, response, new Object(), null); // narrowed to one hospital

        ActingScopeTestSupport.actingAt(ADMIN, UUID.randomUUID());
        HospitalContextHolder.seal();
        interceptor.afterCompletion(request, response, new Object(), null); // staff

        ActingScopeTestSupport.globalSuperAdmin(ADMIN);
        HospitalContextHolder.seal();
        response.setStatus(403);
        interceptor.afterCompletion(request, response, new Object(), null); // refused
        response.setStatus(200);
        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException()); // failed

        verify(audit, never()).recordGlobalViewRequest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a view that wrote its own labelled row gets no second one")
    void aLabelledViewIsNotRecordedTwice() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).principalUserId(ADMIN).build());
        HospitalContextHolder.seal();
        request.setAttribute(GlobalViewAuditInterceptor.ALREADY_RECORDED, Boolean.TRUE);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(audit, never()).recordGlobalViewRequest(any(), any(), any(), any());
    }
}
