package com.example.hms.security;

import com.example.hms.repository.UserRepository;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code X-Hospital-Id} through the two real authentication filters, for a
 * principal whose permitted hospital set is EMPTY.
 *
 * <p>The shared override used to accept the header whenever that set was
 * empty, so every such principal could pin any hospital as its active one.
 * The set is empty for three ordinary callers, each exercised here through
 * the filter that serves it:
 * <ul>
 *   <li>a patient: ROLE_PATIENT is granted as a global (no-hospital)
 *       assignment, and the permitted set is built from hospital ids only;</li>
 *   <li>a clinician whose assignments were revoked after sign-in: the set is
 *       read live from the assignment table (E9 #55), while the authorities
 *       still come from the token until it expires;</li>
 *   <li>a Keycloak token that carries no {@code hospital_id} or
 *       {@code role_assignments} claim.</li>
 * </ul>
 * Each must keep the context its token gave it. The controls show the same
 * wiring still pins an in-scope hospital, so a pass is not the header simply
 * never reaching the override.
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
    private JwtTokenProvider provider;
    private JwtAuthenticationFilter jwtFilter;

    private final KeycloakHospitalContextFilter keycloakFilter = new KeycloakHospitalContextFilter(
        new KeycloakHospitalContextResolver(),
        new IdleSessionGate(mock(IdleSessionTracker.class), ""),
        mock(UserRepository.class));

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, accessor);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");
        provider.init();

        // Every other gate the legacy filter runs is left open (Mockito
        // defaults: not blacklisted, no global revocation, not idle, no
        // blocked tenant), so the only thing under test is the override.
        jwtFilter = new JwtAuthenticationFilter(
            provider,
            mock(TokenBlacklistService.class),
            mock(WsTicketService.class),
            userDetailsService,
            mock(OrganizationLifecycleStatusService.class),
            mock(HospitalLifecycleStatusService.class),
            mock(GlobalSessionRevocationService.class),
            mock(IdleSessionGate.class));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    // ── Legacy HMS token (JwtAuthenticationFilter) ─────────────────────

    @Test
    @DisplayName("a patient's global assignment gives no hospital to pin: the header is ignored")
    void patientCannotPinAHospitalThroughTheHeader() throws Exception {
        givenPrincipal("patient.p", ROLE_PATIENT);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(globalPatient()));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "patient.p", List.of(ROLE_PATIENT)));

        HospitalContext seen = throughJwtFilter(token, HOSPITAL_B);

        assertThat(seen.getPermittedHospitalIds())
            .as("precondition: a global ROLE_PATIENT assignment yields an empty permitted set")
            .isEmpty();
        assertThat(seen.getActiveHospitalId())
            .as("an empty permitted set must not let the header choose the active hospital")
            .isNull();
        assertThat(seen.isHeaderOverridden()).isFalse();
        assertThat(seen.pinnedHospitalId()).isNull();
    }

    @Test
    @DisplayName("a clinician revoked after sign-in cannot re-enter any hospital through the header")
    void revokedClinicianCannotPinAHospitalThroughTheHeader() throws Exception {
        givenPrincipal("dr.revoked", ROLE_DOCTOR);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(doctorAt(HOSPITAL_A, true)));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "dr.revoked", List.of(ROLE_DOCTOR)));

        // Revoked after the token was minted; the token still says ROLE_DOCTOR.
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(doctorAt(HOSPITAL_A, false)));

        HospitalContext foreign = throughJwtFilter(token, HOSPITAL_B);
        assertThat(foreign.getPermittedHospitalIds()).isEmpty();
        assertThat(foreign.getActiveHospitalId())
            .as("a hospital the clinician never held must not become active")
            .isNull();
        assertThat(foreign.isHeaderOverridden()).isFalse();

        HospitalContext former = throughJwtFilter(token, HOSPITAL_A);
        assertThat(former.getActiveHospitalId())
            .as("nor may the hospital the assignment was revoked at")
            .isNull();
        assertThat(former.isHeaderOverridden()).isFalse();
    }

    @Test
    @DisplayName("control: the same wiring pins an in-scope hospital and ignores an out-of-scope one")
    void assignedClinicianStillSwitchesWithinScope() throws Exception {
        givenPrincipal("dr.two", ROLE_DOCTOR);
        when(accessor.findAssignmentsForUser(USER_ID))
            .thenReturn(List.of(doctorAt(HOSPITAL_A, true), doctorAt(HOSPITAL_B, true)));
        String token = provider.generateAccessToken(
            new TokenUserDescriptor(USER_ID, "dr.two", List.of(ROLE_DOCTOR)));

        HospitalContext switched = throughJwtFilter(token, HOSPITAL_B);
        assertThat(switched.getActiveHospitalId()).isEqualTo(HOSPITAL_B);
        assertThat(switched.isHeaderOverridden()).isTrue();

        HospitalContext outside = throughJwtFilter(token, UUID.randomUUID());
        assertThat(outside.getActiveHospitalId()).isEqualTo(HOSPITAL_A);
        assertThat(outside.isHeaderOverridden()).isFalse();
    }

    // ── Keycloak token (KeycloakHospitalContextFilter) ─────────────────

    @Test
    @DisplayName("a Keycloak token with no hospital claims cannot pin a hospital through the header")
    void keycloakTokenWithoutHospitalClaimsCannotPin() throws Exception {
        for (String role : List.of(ROLE_PATIENT, ROLE_DOCTOR)) {
            HospitalContext seen = throughKeycloakFilter(
                Map.of("preferred_username", "kc.user"), role, HOSPITAL_B);

            assertThat(seen.getPermittedHospitalIds()).as(role).isEmpty();
            assertThat(seen.getActiveHospitalId())
                .as("%s: no hospital claim, so no hospital the header may pick", role)
                .isNull();
            assertThat(seen.isHeaderOverridden()).as(role).isFalse();
        }
    }

    @Test
    @DisplayName("control: a Keycloak token scoped to two hospitals still switches between them")
    void keycloakTokenWithHospitalClaimsStillSwitches() throws Exception {
        HospitalContext seen = throughKeycloakFilter(Map.of(
                "preferred_username", "kc.doctor",
                "hospital_id", HOSPITAL_A.toString(),
                "role_assignments", List.of("DOCTOR@" + HOSPITAL_A, "DOCTOR@" + HOSPITAL_B)),
            ROLE_DOCTOR, HOSPITAL_B);

        assertThat(seen.getActiveHospitalId()).isEqualTo(HOSPITAL_B);
        assertThat(seen.isHeaderOverridden()).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private HospitalContext throughJwtFilter(String token, UUID headerHospital) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", REQUEST_PATH);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader(HospitalContextRequestOverrides.HEADER_HOSPITAL_ID, headerHospital.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        FilterChain chain = (req, resp) -> seen.set(HospitalContextHolder.getContextOrEmpty());

        jwtFilter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();

        assertThat(seen.get())
            .as("the request must reach the chain (status %s)", response.getStatus())
            .isNotNull();
        assertThat(seen.get().getPrincipalUserId())
            .as("the legacy filter must have built the context from the real token")
            .isEqualTo(USER_ID);
        return seen.get();
    }

    private HospitalContext throughKeycloakFilter(Map<String, Object> claims, String role,
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
        AtomicReference<HospitalContext> seen = new AtomicReference<>();
        FilterChain chain = (req, resp) -> seen.set(HospitalContextHolder.getContextOrEmpty());

        keycloakFilter.doFilter(request, new MockHttpServletResponse(), chain);
        SecurityContextHolder.clearContext();

        assertThat(seen.get()).isNotNull();
        return seen.get();
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
