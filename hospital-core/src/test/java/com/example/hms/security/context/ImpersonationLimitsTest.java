package com.example.hms.security.context;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpersonationLimitsTest {

    private final com.example.hms.service.AuditEventLogService audit =
        org.mockito.Mockito.mock(com.example.hms.service.AuditEventLogService.class);
    private final ImpersonationLimits limits = new ImpersonationLimits(() -> audit);

    @AfterEach
    void tearDown() {
        ImpersonationContextHolder.clear();
    }

    @Test
    @DisplayName("under impersonation every write on the account paths is refused; reads are not")
    void refusesWritesUnderImpersonation() {
        ImpersonationContextHolder.set(ImpersonationContext.of(UUID.randomUUID(), "support.agent"));

        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            assertThatThrownBy(() -> limits.preHandle(request(method), new MockHttpServletResponse(), null))
                .as(method).isInstanceOf(ClientSafeAccessDeniedException.class);
        }
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            assertThat(limits.preHandle(request(method), new MockHttpServletResponse(), null)).as(method).isTrue();
        }
        // Each refused attempt is in the audit trail.
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(4)).logEvent(org.mockito.ArgumentMatchers.argThat(e ->
            e.getEventType() == com.example.hms.enums.AuditEventType.SECURITY_ALERT_TRIGGERED
                && e.getStatus() == com.example.hms.enums.AuditStatus.FAILURE));
    }

    @Test
    @DisplayName("without an audit service (a test slice) the refusal still holds")
    void refusesWithoutAnAuditService() {
        ImpersonationContextHolder.set(ImpersonationContext.of(UUID.randomUUID(), "support.agent"));
        ImpersonationLimits bare = new ImpersonationLimits(() -> null);
        assertThatThrownBy(() -> bare.preHandle(request("POST"), new MockHttpServletResponse(), null))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("the user's own session changes its own account")
    void allowsTheUserThemselves() {
        assertThat(limits.preHandle(request("POST"), new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    @DisplayName("the rule covers the sign-in and recovery paths, including ones added later under them")
    void coversTheAccountPaths() {
        assertThat(ImpersonationLimits.ACCOUNT_PATHS).containsExactly("/auth/me/**", "/auth/credentials/**");
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/auth/me/change-username");
    }
}
