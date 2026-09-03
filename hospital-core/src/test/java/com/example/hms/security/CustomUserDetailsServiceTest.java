package com.example.hms.security;

import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Authority-derivation contract, pinned because option A (2026-09-02) makes
 * assignment activity the verification gate: {@code assignRole} syncs every
 * new role into {@code user_roles} immediately, so the global fallback must
 * never resurrect a role whose assignments are all still inactive —
 * otherwise verifying ONE assignment (which activates the user) would
 * silently grant every sibling unverified role.
 */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    private User user;
    private Role doctorRole;
    private Role nurseRole;
    private Role legacyRole;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("jdoe");
        user.setActive(true);

        doctorRole = role("ROLE_DOCTOR");
        nurseRole = role("ROLE_NURSE");
        legacyRole = role("ROLE_LAB_SCIENTIST");

        // user_roles carries all three (assignRole syncs eagerly).
        user.getUserRoles().add(UserRole.builder().role(doctorRole).build());
        user.getUserRoles().add(UserRole.builder().role(nurseRole).build());
        user.getUserRoles().add(UserRole.builder().role(legacyRole).build());

        when(userRepository.findByUsernameIgnoreCase("jdoe")).thenReturn(Optional.of(user));
    }

    @Test
    void inactiveAssignmentRolesAreNotResurrectedByTheGlobalFallback() {
        // DOCTOR verified (active assignment), NURSE not yet verified
        // (inactive assignment), LAB_SCIENTIST predates hospital scoping
        // (user_roles row only, no assignment at all).
        when(assignmentRepository.findByUser(user)).thenReturn(Set.of(
            assignment(doctorRole, true),
            assignment(nurseRole, false)));

        Set<String> authorities = authorityCodes();

        assertThat(authorities)
            .as("verified role and the legacy no-assignment fallback are granted")
            .contains("ROLE_DOCTOR", "ROLE_LAB_SCIENTIST");
        assertThat(authorities)
            .as("a role whose only assignment is inactive must NOT ride in via user_roles")
            .doesNotContain("ROLE_NURSE");
    }

    @Test
    void activeAssignmentAnywhereGrantsTheRoleEvenWithAnInactiveSibling() {
        // Multi-hospital: the same role verified at one hospital and pending
        // at another still counts as granted.
        when(assignmentRepository.findByUser(user)).thenReturn(Set.of(
            assignment(doctorRole, true),
            assignment(doctorRole, false)));

        assertThat(authorityCodes()).contains("ROLE_DOCTOR");
    }

    private Set<String> authorityCodes() {
        return service.loadUserByUsername("jdoe").getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static Role role(String code) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setCode(code);
        r.setName(code);
        return r;
    }

    private static UserRoleHospitalAssignment assignment(Role r, boolean active) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(r);
        a.setActive(active);
        return a;
    }
}
