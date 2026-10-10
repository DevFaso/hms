package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.MfaEnrollmentRequiredException;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The provider MFA gate (provider plan AC-13, T4) on its own: what counts as a
 * second factor on each auth path, who is asked (the confinement rule on live
 * assignments, never a role list), who the legacy login challenges, and the
 * 403 it answers. {@code ProviderMfaGateIT} drives it through the real chain.
 */
class ProviderMfaGateTest {

    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID laboratoryId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final TenantRoleAssignmentAccessor accessor = mock(TenantRoleAssignmentAccessor.class);
    private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);

    @SuppressWarnings("unchecked")
    private ProviderMfaGate gate(JwtTokenProvider tokenProvider, LocaleResolver localeResolver,
                                 HandlerExceptionResolver exceptionResolver) {
        ObjectProvider<JwtTokenProvider> t = mock(ObjectProvider.class);
        when(t.getIfAvailable()).thenReturn(tokenProvider);
        ObjectProvider<LocaleResolver> l = mock(ObjectProvider.class);
        when(l.getIfAvailable()).thenReturn(localeResolver);
        ObjectProvider<HandlerExceptionResolver> r = mock(ObjectProvider.class);
        when(r.getIfAvailable()).thenReturn(exceptionResolver);
        return new ProviderMfaGate(accessor, t, l, r);
    }

    private ProviderMfaGate gate() {
        return gate(tokens, null, resolver);
    }

    private static Authentication keycloak(Object amr) {
        Jwt.Builder jwt = Jwt.withTokenValue("kc").header("alg", "RS256").subject("sub").claim("azp", "hms-portal");
        if (amr != null) {
            jwt.claim("amr", amr);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_PHARMACIST")));
    }

    private static Authentication legacy(Object credentials) {
        return new UsernamePasswordAuthenticationToken("pharm", credentials,
            List.of(new SimpleGrantedAuthority("ROLE_PHARMACIST")));
    }

    private HospitalContext pharmacist() {
        return HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(Set.of(pharmacyId))
            .providerFacilityTypes(Set.of(FacilityType.PHARMACY))
            .assignedRoles(Set.of("ROLE_PHARMACIST"))
            .build();
    }

    private HospitalContext doctor() {
        return HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(Set.of(hospitalId))
            .providerFacilityTypes(Set.of())
            .assignedRoles(Set.of("ROLE_DOCTOR"))
            .build();
    }

    @Test
    @DisplayName("Keycloak: otp in amr is the proof, and nothing else is")
    void keycloakProof() {
        ProviderMfaGate gate = gate();

        assertThat(gate.secondFactorPresented(keycloak(List.of("pwd", "otp")))).isTrue();
        assertThat(gate.secondFactorPresented(keycloak("otp"))).isTrue();
        assertThat(gate.secondFactorPresented(keycloak(List.of("pwd")))).isFalse();
        assertThat(gate.secondFactorPresented(keycloak(null))).isFalse();
    }

    @Test
    @DisplayName("legacy: the issuer decides from the bearer the filter keeps as credentials")
    void legacyProof() {
        when(tokens.hasSecondFactor("after.totp")).thenReturn(true);
        when(tokens.hasSecondFactor("password.only")).thenReturn(false);
        ProviderMfaGate gate = gate();

        assertThat(gate.secondFactorPresented(legacy("after.totp"))).isTrue();
        assertThat(gate.secondFactorPresented(legacy("password.only"))).isFalse();
        // The ws ticket's authentication has no credentials; no authentication at all.
        assertThat(gate.secondFactorPresented(legacy(null))).isFalse();
        assertThat(gate.secondFactorPresented(null)).isFalse();
        // No issuer bean: never proof.
        assertThat(gate(null, null, resolver).secondFactorPresented(legacy("after.totp"))).isFalse();
    }

    @Test
    @DisplayName("the gate asks a provider user only, never a hospital user or a verified super-admin")
    void whoIsAsked() {
        ProviderMfaGate gate = gate();
        HospitalContext superAdmin = HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .providerFacilityTypes(Set.of(FacilityType.LABORATORY))
            .assignedRoles(Set.of("ROLE_SUPER_ADMIN"))
            .superAdmin(true)
            .build();

        assertThat(gate.refuses(pharmacist(), keycloak(null))).isTrue();
        assertThat(gate.refuses(pharmacist(), keycloak(List.of("pwd", "otp")))).isFalse();
        assertThat(gate.refuses(doctor(), keycloak(null))).isFalse();
        assertThat(gate.refuses(superAdmin, keycloak(null))).isFalse();
        assertThat(gate.refuses(null, null)).isFalse();
    }

    @Test
    @DisplayName("the legacy login challenges a live provider assignment, whatever the role")
    void loginChallenge() {
        UUID labScientist = UUID.randomUUID();
        UUID hospitalScientist = UUID.randomUUID();
        UUID formerProvider = UUID.randomUUID();
        when(accessor.findAssignmentsForUser(labScientist)).thenReturn(List.of(
            new TenantRoleAssignment(laboratoryId, null, "ROLE_LAB_SCIENTIST", "ROLE_LAB_SCIENTIST", true,
                FacilityType.LABORATORY)));
        when(accessor.findAssignmentsForUser(hospitalScientist)).thenReturn(List.of(
            new TenantRoleAssignment(hospitalId, null, "ROLE_LAB_SCIENTIST", "ROLE_LAB_SCIENTIST", true,
                FacilityType.HOSPITAL)));
        when(accessor.findAssignmentsForUser(formerProvider)).thenReturn(List.of(
            new TenantRoleAssignment(pharmacyId, null, "ROLE_PHARMACIST", "ROLE_PHARMACIST", false,
                FacilityType.PHARMACY)));
        ProviderMfaGate gate = gate();

        assertThat(gate.isProviderUser(labScientist, "lab")).isTrue();
        assertThat(gate.isProviderUser(hospitalScientist, "hlab")).isFalse();
        assertThat(gate.isProviderUser(formerProvider, "former")).isFalse();
        assertThat(gate.isProviderUser(null, "nobody")).isFalse();
    }

    @Test
    @DisplayName("the refusal is mfa.enrollment.required through the resolvers, in the resolved language")
    void refusal() throws Exception {
        LocaleResolver french = mock(LocaleResolver.class);
        when(french.resolveLocale(any())).thenReturn(Locale.FRENCH);
        when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");

        gate(tokens, french, resolver).refuse(request, new MockHttpServletResponse());

        ArgumentCaptor<Exception> refusal = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), isNull(), refusal.capture());
        assertThat(refusal.getValue()).isInstanceOf(MfaEnrollmentRequiredException.class);
        verify(french).resolveLocale(request);
    }

    @Test
    @DisplayName("without a resolver the refusal is still a 403")
    void bareRefusal() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        gate(tokens, null, null).refuse(new MockHttpServletRequest("GET", "/api/notifications"), response);

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
