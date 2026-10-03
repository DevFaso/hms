package com.example.hms.security;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code X-Hospital-Id} through the two real authentication filters
 * (docs/security/tenant-resolution.md Q3, option A): a hospital the caller may
 * not act at, named explicitly, is REFUSED with a 403 carrying the reason —
 * before any controller runs — never silently replaced by another hospital.
 *
 * <p>The callers exercised: a patient (a global, no-hospital assignment); a
 * clinician revoked after sign-in, who names a hospital they never held
 * ({@code NOT_PERMITTED}, the probe) or the one they were revoked at
 * ({@code NO_LONGER_PERMITTED}, the stale chip the portal answers by
 * re-reading its scope); a Keycloak principal with no local account. The
 * controls show the same wiring still pins a held hospital, on both paths.
 */
class HospitalHeaderEmptyScopeFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final String SECRET = "dev-secret-change-me-in-production-minimum-256-bits-long!!";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String REQUEST_PATH = "/api/me/patients";

    private final HospitalUserDetailsService userDetailsService = mock(HospitalUserDetailsService.class);
    private final TenantRoleAssignmentAccessor accessor = mock(TenantRoleAssignmentAccessor.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private JwtTokenProvider provider;
    private JwtAuthenticationFilter jwtFilter;
    private KeycloakHospitalContextFilter keycloakFilter;

    /** What a request produced: the status, and the context the chain saw (null when refused before it). */
    private record Outcome(int status, String body, HospitalContext context) { }

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
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        ActingScopeResolver resolver = new ActingScopeResolver(accessor, assignmentRepository, auditProvider);
        HospitalLifecycleStatusService hospitalLifecycle = mock(HospitalLifecycleStatusService.class);
        OrganizationLifecycleStatusService organizationLifecycle = mock(OrganizationLifecycleStatusService.class);
        when(hospitalLifecycle.getBlockedHospitalIds()).thenReturn(Set.of());
        when(organizationLifecycle.getBlockedOrganizationIds()).thenReturn(Set.of());
        TenantLifecycleGate lifecycleGate = new TenantLifecycleGate(organizationLifecycle, hospitalLifecycle);

        // Every other gate the legacy filter runs is left open (not
        // blacklisted, no global revocation, not idle, no blocked tenant),
        // so what is under test is the header.
        jwtFilter = new JwtAuthenticationFilter(
            provider,
            mock(TokenBlacklistService.class),
            mock(WsTicketService.class),
            userDetailsService,
            lifecycleGate,
            resolver,
            mock(GlobalSessionRevocationService.class),
            mock(IdleSessionGate.class));
        keycloakFilter = new KeycloakHospitalContextFilter(
            new KeycloakHospitalContextResolver(userRepository, resolver), resolver,
            new IdleSessionGate(mock(IdleSessionTracker.class), ""), lifecycleGate, userRepository);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    // ── Legacy HMS token (JwtAuthenticationFilter) ─────────────────────

    @Test
    @DisplayName("a patient naming a hospital is refused 403 NOT_PERMITTED before the chain, and audited")
    void patientNamingAHospitalIsRefused() throws Exception {
        givenPrincipal("patient.p", ROLE_PATIENT);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(globalPatient()));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "patient.p", List.of(ROLE_PATIENT)));

        Outcome outcome = throughJwtFilter(token, HOSPITAL_B);

        assertRefused(outcome, ActingScope.Reason.NOT_PERMITTED);
        verify(audit).recordRefusal(USER_ID, "patient.p", HOSPITAL_B,
            ActingScope.Reason.NOT_PERMITTED, ActingScope.Source.HEADER);
    }

    @Test
    @DisplayName("a clinician revoked after sign-in: a never-held hospital is NOT_PERMITTED, the revoked one NO_LONGER_PERMITTED")
    void revokedClinicianIsRefusedWithTheRightReason() throws Exception {
        givenPrincipal("dr.revoked", ROLE_DOCTOR);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(doctorAt(HOSPITAL_A, true)));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "dr.revoked", List.of(ROLE_DOCTOR)));

        // Revoked after the token was minted; the token still says ROLE_DOCTOR.
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(doctorAt(HOSPITAL_A, false)));
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveFalse(USER_ID, HOSPITAL_A)).thenReturn(true);

        assertRefused(throughJwtFilter(token, HOSPITAL_B), ActingScope.Reason.NOT_PERMITTED);
        assertRefused(throughJwtFilter(token, HOSPITAL_A), ActingScope.Reason.NO_LONGER_PERMITTED);
    }

    @Test
    @DisplayName("control: a held hospital is acted at; an unheld one is refused, not replaced by the other")
    void assignedClinicianSwitchesWithinScopeAndIsRefusedOutsideIt() throws Exception {
        givenPrincipal("dr.two", ROLE_DOCTOR);
        when(accessor.findAssignmentsForUser(USER_ID))
            .thenReturn(List.of(doctorAt(HOSPITAL_A, true), doctorAt(HOSPITAL_B, true)));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "dr.two", List.of(ROLE_DOCTOR)));

        Outcome switched = throughJwtFilter(token, HOSPITAL_B);
        assertThat(switched.context()).isNotNull();
        assertThat(ActingScopeResolver.scopeOf(switched.context()))
            .isEqualTo(new ActingScope.Pinned(HOSPITAL_B, ActingScope.Source.HEADER));

        assertRefused(throughJwtFilter(token, UUID.randomUUID()), ActingScope.Reason.NOT_PERMITTED);
    }

    @Test
    @DisplayName("a malformed header is refused, not ignored")
    void malformedHeaderIsRefused() throws Exception {
        givenPrincipal("dr.one", ROLE_DOCTOR);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(doctorAt(HOSPITAL_A, true)));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "dr.one", List.of(ROLE_DOCTOR)));

        assertRefused(throughJwtFilter(token, "not-a-uuid"), ActingScope.Reason.NOT_PERMITTED);
    }

    // ── Keycloak token (KeycloakHospitalContextFilter) ─────────────────

    @Test
    @DisplayName("a Keycloak principal with no local account naming a hospital is refused")
    void unlinkedKeycloakPrincipalIsRefused() throws Exception {
        for (String role : List.of(ROLE_PATIENT, ROLE_DOCTOR)) {
            Outcome outcome = throughKeycloakFilter(
                Map.of("preferred_username", "kc.user",
                    "hospital_id", HOSPITAL_B.toString(),
                    "role_assignments", List.of("DOCTOR@" + HOSPITAL_B)),
                role, HOSPITAL_B);
            assertRefused(outcome, ActingScope.Reason.NOT_PERMITTED);
        }
        verify(accessor, never()).findAssignmentsForUser(any());
    }

    @Test
    @DisplayName("control: a linked Keycloak principal holding two hospitals switches between them")
    void linkedKeycloakPrincipalSwitches() throws Exception {
        User local = new User();
        local.setId(USER_ID);
        local.setUsername("kc.user");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(local));
        when(accessor.findAssignmentsForUser(USER_ID))
            .thenReturn(List.of(doctorAt(HOSPITAL_A, true), doctorAt(HOSPITAL_B, true)));

        Outcome outcome = throughKeycloakFilter(
            Map.of("preferred_username", "kc.user", "appUserId", USER_ID.toString()), ROLE_DOCTOR, HOSPITAL_B);

        assertThat(outcome.context()).isNotNull();
        assertThat(ActingScopeResolver.scopeOf(outcome.context()))
            .isEqualTo(new ActingScope.Pinned(HOSPITAL_B, ActingScope.Source.HEADER));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void assertRefused(Outcome outcome, ActingScope.Reason reason) {
        assertThat(outcome.status()).isEqualTo(403);
        assertThat(outcome.body()).contains("\"code\":\"hospital_scope_refused\"")
            .contains("\"reason\":\"" + reason.name() + "\"");
        assertThat(outcome.context()).as("refused before any controller runs").isNull();
    }

    private Outcome throughJwtFilter(String token, UUID headerHospital) throws Exception {
        return throughJwtFilter(token, headerHospital.toString());
    }

    private Outcome throughJwtFilter(String token, String headerHospital) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", REQUEST_PATH);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, headerHospital);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        FilterChain chain = (req, resp) -> seen.set(HospitalContextHolder.getContextOrEmpty());

        jwtFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();

        if (seen.get() != null) {
            assertThat(seen.get().getPrincipalUserId())
                .as("the legacy filter must have built the context from the real token")
                .isEqualTo(USER_ID);
        }
        return new Outcome(response.getStatus(), response.getContentAsString(), seen.get());
    }

    private Outcome throughKeycloakFilter(Map<String, Object> claims, String role,
                                         UUID headerHospital) throws Exception {
        Jwt jwt = Jwt.withTokenValue("kc-token")
            .header("alg", "RS256")
            .subject(UUID.randomUUID().toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claims(c -> c.putAll(claims))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), "kc.user"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", REQUEST_PATH);
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, headerHospital.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        FilterChain chain = (req, resp) -> seen.set(HospitalContextHolder.getContextOrEmpty());

        keycloakFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();

        return new Outcome(response.getStatus(), response.getContentAsString(), seen.get());
    }

    private void givenPrincipal(String username, String role) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        when(userDetailsService.loadUserByUsername(anyString()))
            .thenReturn(new PrincipalStub(username, authorities));
    }

    private static TenantRoleAssignment globalPatient() {
        return new TenantRoleAssignment(null, null, ROLE_PATIENT, "Patient", true);
    }

    private static TenantRoleAssignment doctorAt(UUID hospitalId, boolean active) {
        return new TenantRoleAssignment(hospitalId, ORG, ROLE_DOCTOR, "Doctor", active);
    }

    private static final class PrincipalStub implements HospitalUserDetails {
        private final String username;
        private final Collection<? extends GrantedAuthority> authorities;

        private PrincipalStub(String username, Collection<? extends GrantedAuthority> authorities) {
            this.username = username;
            this.authorities = authorities;
        }

        @Override public UUID getUserId() { return USER_ID; }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
        @Override public String getPassword() { return ""; }
        @Override public String getUsername() { return username; }
        @Override public boolean isAccountNonExpired() { return true; }
        @Override public boolean isAccountNonLocked() { return true; }
        @Override public boolean isCredentialsNonExpired() { return true; }
        @Override public boolean isEnabled() { return true; }
    }
}
