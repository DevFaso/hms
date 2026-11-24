package com.example.hms.security;

import com.example.hms.model.User;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An account that must set its own password (a temporary password, or a rotation not done) uses no API until it
 * has: the real {@link JwtAuthenticationFilter} with a real token refuses every request but the account-setup ones
 * with a 403 whose {@code code} is {@code password_change_required}, whatever the client does.
 */
class PasswordChangeGateFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SECRET = "dev-secret-change-me-in-production-minimum-256-bits-long!!";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private final HospitalUserDetailsService userDetailsService = mock(HospitalUserDetailsService.class);
    private final TenantRoleAssignmentAccessor accessor = mock(TenantRoleAssignmentAccessor.class);
    private JwtTokenProvider provider;
    private JwtAuthenticationFilter filter;
    private User account;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, accessor);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");
        provider.init();

        ObjectProvider<CrossTenantReadAudit> auditProvider = mock(ObjectProvider.class);
        ActingScopeResolver resolver = new ActingScopeResolver(
            accessor, mock(UserRoleHospitalAssignmentRepository.class), auditProvider);
        HospitalLifecycleStatusService hospitalLifecycle = mock(HospitalLifecycleStatusService.class);
        OrganizationLifecycleStatusService organizationLifecycle = mock(OrganizationLifecycleStatusService.class);
        when(hospitalLifecycle.getBlockedHospitalIds()).thenReturn(Set.of());
        when(organizationLifecycle.getBlockedOrganizationIds()).thenReturn(Set.of());

        filter = new JwtAuthenticationFilter(
            provider,
            mock(TokenBlacklistService.class),
            mock(WsTicketService.class),
            userDetailsService,
            new TenantLifecycleGate(organizationLifecycle, hospitalLifecycle),
            resolver,
            mock(GlobalSessionRevocationService.class),
            mock(IdleSessionGate.class));

        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(
            new TenantRoleAssignment(null, null, ROLE_PATIENT, "Patient", true, null)));

        account = new User();
        account.setId(USER_ID);
        account.setUsername("awa.t");
        account.setPasswordHash("hash");
        account.setActive(true);
        // Set before the token is minted: a token older than the last password change is refused (QA D114).
        account.setPasswordChangedAt(LocalDateTime.now().minusHours(1));
        when(userDetailsService.loadUserByUsername(anyString())).thenAnswer(inv ->
            new CustomUserDetails(account, List.of(new SimpleGrantedAuthority(ROLE_PATIENT))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @ParameterizedTest(name = "{0} {1} is refused until the password is changed")
    @CsvSource({
        "GET,/me/patient/appointments",
        "GET,/patients",
        "POST,/me/patient/refills",
        "POST,/auth/ws-ticket",
        "PUT,/auth/credentials/mfa",
        "GET,/auth/session/bootstrap/",
        "GET,/me/patient/profile/../appointments",
    })
    void everyOtherRequestIsRefusedWithTheCode(String method, String path) throws Exception {
        account.setForcePasswordChange(true);

        Outcome outcome = send(method, path);

        assertThat(outcome.reachedTheController()).isFalse();
        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.body())
            .contains("\"code\":\"password_change_required\"")
            .doesNotContain("awa.t");
    }

    @ParameterizedTest(name = "{0} {1} stays open: it is how the password gets changed")
    @CsvSource({
        "POST,/auth/me/change-password",
        "POST,/auth/me/complete-setup",
        "GET,/auth/session/bootstrap",
        "GET,/auth/credentials/me",
        "GET,/me/patient/profile",
        "POST,/auth/token/refresh",
        "GET,/auth/csrf-token",
    })
    void theAccountSetupRequestsStayOpen(String method, String path) throws Exception {
        account.setForcePasswordChange(true);

        Outcome outcome = send(method, path);

        assertThat(outcome.reachedTheController()).isTrue();
        assertThat(outcome.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an account on its own password is not held back")
    void anAccountOnItsOwnPasswordIsNotHeldBack() throws Exception {
        account.setForcePasswordChange(false);

        Outcome outcome = send("GET", "/me/patient/appointments");

        assertThat(outcome.reachedTheController()).isTrue();
        assertThat(outcome.status()).isEqualTo(200);
    }

    private record Outcome(int status, String body, boolean reachedTheController) { }

    private Outcome send(String method, String path) throws Exception {
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, account.getUsername(), List.of(ROLE_PATIENT)));
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, resp) -> reached.set(true));
        SecurityContextHolder.clearContext();

        return new Outcome(response.getStatus(), response.getContentAsString(), reached.get());
    }
}
