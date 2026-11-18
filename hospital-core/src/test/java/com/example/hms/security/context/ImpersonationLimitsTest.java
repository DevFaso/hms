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
            MockHttpServletRequest write = request(method);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThatThrownBy(() -> limits.preHandle(write, response, null))
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
        MockHttpServletRequest write = request("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> bare.preHandle(write, response, null))
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
        assertThat(ImpersonationLimits.ACCOUNT_PATHS)
            .containsExactly("/auth/me/**", "/auth/credentials/**", "/auth/verify-password");
    }

    @Test
    @DisplayName("billing-money plan PR 8 review: the money paths refuse every write under impersonation, with an audit row")
    void theMoneyRuleRefusesWritesAndKeepsReads() {
        ImpersonationContextHolder.set(ImpersonationContext.of(UUID.randomUUID(), "support.agent"));
        ImpersonationLimits money = ImpersonationLimits.forMoney(() -> audit);
        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/billing-invoices/x/refunds");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> money.preHandle(write, response, null))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThat(money.preHandle(new MockHttpServletRequest("GET", "/billing-invoices/x/refunds"),
            new MockHttpServletResponse(), null)).isTrue();
        org.mockito.Mockito.verify(audit).logEvent(org.mockito.ArgumentMatchers.argThat(e ->
            e.getEventType() == com.example.hms.enums.AuditEventType.SECURITY_ALERT_TRIGGERED
                && e.getEventDescription().endsWith("(money)")));
        assertThat(ImpersonationLimits.MONEY_PATHS).contains("/billing-invoices/**", "/invoice-items/**",
            "/billing/price-list/**", "/billing/payers/**", "/pharmacy/payments/**", "/pharmacy-sales/**");
    }

    @Test
    @DisplayName("the service-side money check refuses only while impersonating")
    void theServiceSideMoneyCheck() {
        org.assertj.core.api.Assertions.assertThatCode(() -> ImpersonationLimits.refuseMoneyWrite(() -> audit, "copay"))
            .doesNotThrowAnyException();
        ImpersonationContextHolder.set(ImpersonationContext.of(UUID.randomUUID(), "support.agent"));
        assertThatThrownBy(() -> ImpersonationLimits.refuseMoneyWrite(() -> audit, "copay"))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("both refusal keys are in the bundles (FR and EN)")
    void bothRefusalKeysResolve() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        for (java.util.Locale locale : java.util.List.of(java.util.Locale.FRENCH, java.util.Locale.ENGLISH)) {
            for (String key : java.util.List.of(ImpersonationLimits.ACCOUNT_CHANGE_REFUSED,
                    ImpersonationLimits.MONEY_WRITE_REFUSED)) {
                assertThat(com.example.hms.utility.MessageUtil.resolve(locale, key)).as(locale + " " + key)
                    .isNotEqualTo(key);
            }
        }
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/auth/me/change-username");
    }
}
