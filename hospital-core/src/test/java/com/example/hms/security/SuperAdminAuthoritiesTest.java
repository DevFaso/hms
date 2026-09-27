package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design Q10, option A: {@link RoleExpansion#reconcile} and the filters'
 * adapter {@link SuperAdminAuthorities}.
 */
class SuperAdminAuthoritiesTest {

    private static final List<String> EXPANDED_SUPER_ADMIN =
        List.copyOf(RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN")));

    @Test
    @DisplayName("an unbacked ROLE_SUPER_ADMIN loses itself and every inherited role not held on its own")
    void stripsAnUnbackedSuperAdmin() {
        assertThat(RoleExpansion.reconcile(EXPANDED_SUPER_ADMIN, false, Set.of("ROLE_NURSE")))
            .containsExactly("ROLE_NURSE");
        assertThat(RoleExpansion.reconcile(EXPANDED_SUPER_ADMIN, false, Set.of()))
            .as("a legacy user_roles-only super-admin keeps nothing it inherited").isEmpty();
    }

    @Test
    @DisplayName("roles outside the inheritance list are never touched, and doctor equivalence still holds")
    void keepsWhatItDidNotInherit() {
        List<String> token = List.of("ROLE_SUPER_ADMIN", "ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_PHARMACIST", "FACTOR_PASSWORD");
        assertThat(RoleExpansion.reconcile(token, false, Set.of()))
            .as("a surgeon in the token is still a doctor; the pharmacist and the factor stay")
            .containsExactly("ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_PHARMACIST", "FACTOR_PASSWORD");
    }

    @Test
    @DisplayName("a verified super-admin, or a token without the authority, is unchanged")
    void unchangedWhenNothingToReconcile() {
        assertThat(RoleExpansion.reconcile(EXPANDED_SUPER_ADMIN, true, Set.of("ROLE_SUPER_ADMIN")))
            .containsExactlyElementsOf(EXPANDED_SUPER_ADMIN);
        assertThat(RoleExpansion.reconcile(List.of("ROLE_DOCTOR"), false, Set.of()))
            .containsExactly("ROLE_DOCTOR");
    }

    @Test
    @DisplayName("the filters' adapter rebuilds the password-path token and keeps the principal and details")
    void rebuildsThePasswordToken() {
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
            "principal", null, authorities(EXPANDED_SUPER_ADMIN));
        token.setDetails("details");
        HospitalContext demoted = HospitalContext.builder().assignedRoles(Set.of("ROLE_NURSE")).build();

        Authentication reconciled = SuperAdminAuthorities.reconcile(token, demoted);

        assertThat(reconciled).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(reconciled.getPrincipal()).isEqualTo("principal");
        assertThat(reconciled.getDetails()).isEqualTo("details");
        assertThat(reconciled.isAuthenticated()).isTrue();
        assertThat(reconciled.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_NURSE");
    }

    @Test
    @DisplayName("the adapter returns the same token when nothing changes, or when it cannot rebuild the type")
    void sameTokenWhenUnchanged() {
        UsernamePasswordAuthenticationToken doctor = UsernamePasswordAuthenticationToken.authenticated(
            "p", null, authorities(List.of("ROLE_DOCTOR")));
        assertThat(SuperAdminAuthorities.reconcile(doctor, HospitalContext.empty())).isSameAs(doctor);

        TestingAuthenticationToken other = new TestingAuthenticationToken("p", null, "ROLE_SUPER_ADMIN");
        assertThat(SuperAdminAuthorities.reconcile(other, HospitalContext.empty())).isSameAs(other);
        assertThat(SuperAdminAuthorities.reconcile(null, HospitalContext.empty())).isNull();
    }

    private static List<GrantedAuthority> authorities(List<String> names) {
        return names.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }
}
