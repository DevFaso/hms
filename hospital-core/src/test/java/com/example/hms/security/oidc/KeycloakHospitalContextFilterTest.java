package com.example.hms.security.oidc;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KeycloakHospitalContextFilter}: the Keycloak path gets the same
 * context, and the same gates in the same order, as the password path
 * (docs/security/tenant-resolution.md §3.2, §3.6): a live context keyed on
 * the local account, the {@code X-Hospital-Id} header refused when it names a
 * hospital the caller may not use, the tenant lifecycle gate (D14), and the
 * authorities reconciled with the live super-admin signal (Q10, option A).
 */
class KeycloakHospitalContextFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TenantRoleAssignmentAccessor assignments = mock(TenantRoleAssignmentAccessor.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private final HospitalLifecycleStatusService hospitalLifecycle = mock(HospitalLifecycleStatusService.class);
    private final OrganizationLifecycleStatusService organizationLifecycle =
        mock(OrganizationLifecycleStatusService.class);
    // Tracker disabled so the idle gate short-circuits; idle behaviour is
    // covered by IdleSessionGateTest.
    private final IdleSessionGate idleSessionGate = new IdleSessionGate(mock(IdleSessionTracker.class), "");
    private KeycloakHospitalContextFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CrossTenantReadAudit> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        ActingScopeResolver actingScopeResolver = new ActingScopeResolver(assignments, assignmentRepository, auditProvider);
        filter = new KeycloakHospitalContextFilter(
            new KeycloakHospitalContextResolver(userRepository, actingScopeResolver),
            actingScopeResolver, idleSessionGate,
            new TenantLifecycleGate(organizationLifecycle, hospitalLifecycle), userRepository);
        when(hospitalLifecycle.getBlockedHospitalIds()).thenReturn(Set.of());
        when(organizationLifecycle.getBlockedOrganizationIds()).thenReturn(Set.of());
        User local = new User();
        local.setId(USER_ID);
        local.setUsername("dr.alice");
        local.setActive(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(local));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("populates the context from the linked account's live assignments and clears it afterwards")
    void populatesContextFromLiveAssignmentsAndClearsAfterwards() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"));
        signIn(List.of("ROLE_DOCTOR"));

        HospitalContext seen = run(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(seen.getPrincipalUserId()).as("principalUserId is set on Keycloak too").isEqualTo(USER_ID);
        assertThat(seen.getActiveHospitalId()).isEqualTo(HOSPITAL_A);
        assertThat(seen.getPermittedHospitalIds()).containsExactly(HOSPITAL_A);
        assertThat(HospitalContextHolder.getContext()).as("cleared after the chain").isEmpty();
    }

    @Test
    @DisplayName("X-Hospital-Id naming a held hospital settles a multi-hospital caller's scope")
    void honoursAHeldHeader() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"), assignment(HOSPITAL_B, "ROLE_DOCTOR"));
        signIn(List.of("ROLE_DOCTOR"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, HOSPITAL_B.toString());

        HospitalContext seen = run(request, new MockHttpServletResponse());

        assertThat(ActingScopeResolver.scopeOf(seen))
            .isEqualTo(new ActingScope.Pinned(HOSPITAL_B, ActingScope.Source.HEADER));
    }

    @Test
    @DisplayName("X-Hospital-Id naming a hospital held once is refused 403 NO_LONGER_PERMITTED, audited, before the chain")
    void refusesAStaleHeader() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"));
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveFalse(USER_ID, HOSPITAL_B)).thenReturn(true);
        signIn(List.of("ROLE_DOCTOR"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, HOSPITAL_B.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"reason\":\"NO_LONGER_PERMITTED\"");
        verify(chain, never()).doFilter(any(), any());
        verify(audit).recordRefusal(USER_ID, "dr.alice", HOSPITAL_B,
            ActingScope.Reason.NO_LONGER_PERMITTED, ActingScope.Source.HEADER);
    }

    @Test
    @DisplayName("a hospital suspended by its lifecycle answers 423 on Keycloak, as on the password path (D14)")
    void enforcesTheLifecycleGate() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"));
        when(hospitalLifecycle.getBlockedHospitalIds()).thenReturn(Set.of(HOSPITAL_A));
        signIn(List.of("ROLE_DOCTOR"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(423);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("a SUPER_ADMIN realm role with no live assignment loses the authority and what it inherited (Q10)")
    void reconcilesAnUnbackedSuperAdminRole() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"));
        signIn(List.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR", "ROLE_PATIENT"));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, resp) -> seen.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_DOCTOR");
        assertThat(seen.get()).isInstanceOf(JwtAuthenticationToken.class);
    }

    @Test
    @DisplayName("a live SUPER_ADMIN assignment keeps the authorities and is global view")
    void keepsABackedSuperAdmin() throws Exception {
        holds(assignment(null, "ROLE_SUPER_ADMIN"));
        signIn(List.of("ROLE_SUPER_ADMIN", "ROLE_DOCTOR"));
        AtomicReference<Authentication> seenAuth = new AtomicReference<>();
        AtomicReference<HospitalContext> seenContext = new AtomicReference<>();
        FilterChain chain = (req, resp) -> {
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
            seenContext.set(HospitalContextHolder.getContextOrEmpty());
        };

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenAuth.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_SUPER_ADMIN", "ROLE_DOCTOR");
        assertThat(seenContext.get().isGlobalView()).isTrue();
    }

    @Test
    void leavesContextUntouchedForNonJwtAuthentication() throws Exception {
        Authentication anon = new AnonymousAuthenticationToken("k", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(HospitalContextHolder.getContext()).isEmpty();
    }

    @Test
    void clearsContextEvenWhenDownstreamThrows() throws Exception {
        holds(assignment(HOSPITAL_A, "ROLE_DOCTOR"));
        signIn(List.of("ROLE_DOCTOR"));

        FilterChain throwing = mock(FilterChain.class);
        doThrow(new RuntimeException("boom")).when(throwing).doFilter(any(), any());

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwing);
        } catch (RuntimeException expected) {
            // swallow — we only care about the cleanup assertion below
        }

        assertThat(HospitalContextHolder.getContext())
                .as("context must be cleared even when downstream throws")
                .isEmpty();
    }

    @Test
    void refusesKeycloakTokenWhenLocalAccountIsInactive() throws Exception {
        // Option A: accounts start inactive until the emailed code is
        // verified; a valid Keycloak token cannot outrun that.
        User localUser = new User();
        localUser.setActive(false);
        when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(localUser));
        signIn(List.of("ROLE_DOCTOR"));

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, times(0)).doFilter(any(), any());
        assertThat(HospitalContextHolder.getContext())
                .as("no hospital context may be populated for a refused request")
                .isEmpty();
    }

    private HospitalContext run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> seen.set(HospitalContextHolder.getContextOrEmpty()));
        return seen.get();
    }

    private void holds(TenantRoleAssignment... held) {
        when(assignments.findAssignmentsForUser(USER_ID)).thenReturn(List.of(held));
    }

    private static TenantRoleAssignment assignment(UUID hospitalId, String role) {
        return new TenantRoleAssignment(hospitalId, null, role, role, true, com.example.hms.enums.FacilityType.HOSPITAL);
    }

    private static void signIn(List<String> realmRoles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("preferred_username", "dr.alice");
        claims.put("appUserId", USER_ID.toString());
        Instant now = Instant.now();
        Jwt jwt = new Jwt("token", now, now.plusSeconds(60), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt, realmRoles.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList(), "dr.alice"));
    }
}
