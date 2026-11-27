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
import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A session whose screen is locked uses no API until its holder authenticates again: the real
 * {@link JwtAuthenticationFilter}, a real token and the real lock store. Whatever the browser remembers, every
 * request but the ones of {@link SessionLockGate} is refused with a 403 whose {@code code} is
 * {@code session_locked}; unlocking opens the session again; another session of the same account is untouched.
 */
class SessionLockGateFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SECRET = "dev-secret-change-me-in-production-minimum-256-bits-long!!";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String SESSION = "0a6b1c7e-0000-4000-8000-000000000001";
    private static final String OTHER_SESSION = "0a6b1c7e-0000-4000-8000-000000000002";

    private final HospitalUserDetailsService userDetailsService = mock(HospitalUserDetailsService.class);
    private final TenantRoleAssignmentAccessor accessor = mock(TenantRoleAssignmentAccessor.class);
    private final InMemoryTokenBlacklistService store = new InMemoryTokenBlacklistService();
    private JwtTokenProvider provider;
    private JwtAuthenticationFilter filter;

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
            store,
            mock(WsTicketService.class),
            userDetailsService,
            new TenantLifecycleGate(organizationLifecycle, hospitalLifecycle),
            resolver,
            mock(GlobalSessionRevocationService.class),
            mock(IdleSessionGate.class));

        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(
            new TenantRoleAssignment(null, null, ROLE_NURSE, "Nurse", true, null)));

        User account = new User();
        account.setId(USER_ID);
        account.setUsername("awa.t");
        account.setPasswordHash("hash");
        account.setActive(true);
        account.setPasswordChangedAt(LocalDateTime.now().minusHours(1));
        when(userDetailsService.loadUserByUsername(anyString())).thenAnswer(inv ->
            new CustomUserDetails(account, List.of(new SimpleGrantedAuthority(ROLE_NURSE))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @ParameterizedTest(name = "{0} {1} is refused while the screen is locked")
    @CsvSource({
        "GET,/patients",
        "GET,/patients/0a6b1c7e-0000-4000-8000-00000000000a",
        "POST,/encounters",
        "GET,/notifications",
        "POST,/auth/ws-ticket",
        "POST,/auth/me/change-password",
        "GET,/auth/session/bootstrap/",
        "POST,/auth/verify-password/../ws-ticket",
    })
    void aLockedSessionIsRefusedEverythingElse(String method, String path) throws Exception {
        lock(SESSION);

        Outcome outcome = send(method, path, SESSION);

        assertThat(outcome.reachedTheController()).isFalse();
        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.body())
            .contains("\"code\":\"session_locked\"")
            .doesNotContain("awa.t");
    }

    @ParameterizedTest(name = "{0} {1} stays open: it is how the screen is unlocked or the session ended")
    @CsvSource({
        "POST,/auth/verify-password",
        "POST,/auth/session/lock",
        "POST,/auth/session/unlock",
        "GET,/auth/session/bootstrap",
        "POST,/auth/token/refresh",
        "POST,/auth/logout",
    })
    void theWayOutStaysOpen(String method, String path) throws Exception {
        lock(SESSION);

        Outcome outcome = send(method, path, SESSION);

        assertThat(outcome.reachedTheController()).isTrue();
        assertThat(outcome.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a new access token of the locked session is refused too: the lock is the session's, not the token's")
    void aRefreshedTokenOfTheLockedSessionIsStillLocked() throws Exception {
        lock(SESSION);

        assertThat(send("GET", "/patients", SESSION).status()).isEqualTo(403);
        assertThat(send("GET", "/patients", SESSION).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("unlocking opens the session again")
    void unlockingOpensTheSession() throws Exception {
        lock(SESSION);
        store.unlockSession(SESSION);

        Outcome outcome = send("GET", "/patients", SESSION);

        assertThat(outcome.reachedTheController()).isTrue();
    }

    @Test
    @DisplayName("another session of the same account is not locked")
    void anotherSessionIsUntouched() throws Exception {
        lock(SESSION);

        Outcome outcome = send("GET", "/patients", OTHER_SESSION);

        assertThat(outcome.reachedTheController()).isTrue();
    }

    @Test
    @DisplayName("every admitted request maps to a controller method")
    void everyAdmittedRequestMapsToAHandler() {
        Map<RequestMappingInfo, ?> handlers = ControllerMappingRegistry.mapping().getHandlerMethods();
        List<String> unmapped = new ArrayList<>();
        for (String admitted : SessionLockGate.admittedRequests()) {
            String[] parts = admitted.split(" ", 2);
            RequestMethod method = RequestMethod.valueOf(parts[0]);
            boolean mapped = handlers.keySet().stream().anyMatch(info ->
                info.getPatternValues().contains(parts[1])
                    && (info.getMethodsCondition().getMethods().isEmpty()
                        || info.getMethodsCondition().getMethods().contains(method)));
            if (!mapped) {
                unmapped.add(admitted);
            }
        }
        assertThat(unmapped).isEmpty();
    }

    private void lock(String sessionId) {
        long now = System.currentTimeMillis();
        store.lockSession(sessionId, now, now + 172_800_000L);
    }

    private record Outcome(int status, String body, boolean reachedTheController) { }

    private Outcome send(String method, String path, String sessionId) throws Exception {
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "awa.t", List.of(ROLE_NURSE)), null, sessionId);
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
