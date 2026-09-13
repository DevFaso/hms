package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * E9 #55 — the hospital context is built from the LIVE assignment table on
 * every request, not from the claims baked into the token at login.
 *
 * <p>Before this, {@code buildHospitalContext} trusted the token's
 * {@code hospitalIds} / {@code primaryHospitalId} and only fell back to the
 * table when the token carried none. A nurse whose assignment moved from
 * hospital A to hospital B after signing in kept a context saying A until
 * they logged out, while the assignment-based resolver in
 * {@code ControllerAuthUtils} said B — the disagreement behind the
 * "patient is not registered at this hospital" seen on dev on 2026-09-10.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderHospitalContextTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USERNAME = "nurse.b";
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    @Mock
    private HospitalUserDetailsService userDetailsService;

    @Mock
    private TenantRoleAssignmentAccessor accessor;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, accessor);
        ReflectionTestUtils.setField(provider, "jwtSecret",
            "dev-secret-change-me-in-production-minimum-256-bits-long!!");
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");
        provider.init();
    }

    @Test
    @DisplayName("an assignment moved after login re-scopes the context on the next request")
    void liveAssignmentsReplaceTheLoginClaims() {
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(nurseAt(HOSPITAL_A, ORG_A)));
        String token = provider.generateAccessToken(new TokenUserDescriptor(USER_ID, USERNAME, List.of("ROLE_NURSE")));

        // The assignment is moved: A revoked, B granted.
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(nurseAt(HOSPITAL_B, ORG_B)));

        HospitalContext ctx = provider.extractHospitalContext(token, nurseAuthentication());

        assertThat(ctx.getPermittedHospitalIds()).containsExactly(HOSPITAL_B);
        assertThat(ctx.getActiveHospitalId()).isEqualTo(HOSPITAL_B);
        assertThat(ctx.getPermittedOrganizationIds()).containsExactly(ORG_B);
        assertThat(ctx.getActiveOrganizationId()).isEqualTo(ORG_B);
        assertThat(ctx.getPrincipalUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("the token's primary hospital is kept while it is still permitted, so the active scope is stable")
    void primaryIsKeptWhileStillPermitted() {
        when(accessor.findAssignmentsForUser(USER_ID))
            .thenReturn(List.of(nurseAt(HOSPITAL_A, ORG_A), nurseAt(HOSPITAL_B, ORG_A)));
        String token = provider.generateAccessToken(new TokenUserDescriptor(USER_ID, USERNAME, List.of("ROLE_NURSE")));

        // Same two assignments, but the query now returns B first (a newer row).
        when(accessor.findAssignmentsForUser(USER_ID))
            .thenReturn(List.of(nurseAt(HOSPITAL_B, ORG_A), nurseAt(HOSPITAL_A, ORG_A)));

        HospitalContext ctx = provider.extractHospitalContext(token, nurseAuthentication());

        assertThat(ctx.getPermittedHospitalIds()).containsExactlyInAnyOrder(HOSPITAL_A, HOSPITAL_B);
        assertThat(ctx.getActiveHospitalId())
            .as("the primary chosen at login stays active while it is still permitted")
            .isEqualTo(HOSPITAL_A);
    }

    @Test
    @DisplayName("every assignment revoked after login leaves an EMPTY scope, not the token's")
    void revokedAssignmentsFailClosed() {
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(nurseAt(HOSPITAL_A, ORG_A)));
        String token = provider.generateAccessToken(new TokenUserDescriptor(USER_ID, USERNAME, List.of("ROLE_NURSE")));

        TenantRoleAssignment revoked = new TenantRoleAssignment(HOSPITAL_A, ORG_A, "ROLE_NURSE", "Nurse", false);
        when(accessor.findAssignmentsForUser(USER_ID)).thenReturn(List.of(revoked));

        HospitalContext ctx = provider.extractHospitalContext(token, nurseAuthentication());

        assertThat(ctx.getPermittedHospitalIds()).isEmpty();
        assertThat(ctx.getActiveHospitalId()).isNull();
    }

    private static TenantRoleAssignment nurseAt(UUID hospitalId, UUID organizationId) {
        return new TenantRoleAssignment(hospitalId, organizationId, "ROLE_NURSE", "Nurse", true);
    }

    /** The shape the username/password login produces: a HospitalUserDetails principal, no Jwt token. */
    private static Authentication nurseAuthentication() {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_NURSE"));
        return new UsernamePasswordAuthenticationToken(new PrincipalStub(authorities), null, authorities);
    }

    private static final class PrincipalStub implements HospitalUserDetails {
        private final Collection<? extends GrantedAuthority> authorities;

        private PrincipalStub(Collection<? extends GrantedAuthority> authorities) {
            this.authorities = authorities;
        }

        @Override public UUID getUserId() { return USER_ID; }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
        @Override public String getPassword() { return ""; }
        @Override public String getUsername() { return USERNAME; }
        @Override public boolean isAccountNonExpired() { return true; }
        @Override public boolean isAccountNonLocked() { return true; }
        @Override public boolean isCredentialsNonExpired() { return true; }
        @Override public boolean isEnabled() { return true; }
    }
}
