package com.example.hms.security.audit;

import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActingAssignmentsTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOSPITAL = UUID.randomUUID();

    private static UserRoleHospitalAssignment row(String code) {
        Role role = new Role();
        role.setCode(code);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(role);
        return a;
    }

    private static UsernamePasswordAuthenticationToken token(String... roles) {
        return new UsernamePasswordAuthenticationToken("multi", null,
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    @DisplayName("QA D1632: the row of the token's role wins over the first active row")
    void picksTheTokensRole() {
        UserRoleHospitalAssignment doctor = row("ROLE_DOCTOR");
        UserRoleHospitalAssignment lab = row("ROLE_LAB_DIRECTOR");
        UserRoleHospitalAssignmentRepository repo = mock(UserRoleHospitalAssignmentRepository.class);
        when(repo.findAllByUser_IdAndHospital_IdAndActiveTrue(USER, HOSPITAL)).thenReturn(List.of(doctor, lab));

        var auth = token("ROLE_LAB_DIRECTOR");
        var picked = ActingAssignments.resolve(repo, USER, HOSPITAL, auth).orElseThrow();

        assertThat(picked).isSameAs(lab);
        assertThat(ActingAssignments.roleName(picked, ActingAssignments.actingRoles(auth))).isEqualTo("LAB_DIRECTOR");
    }

    @Test
    @DisplayName("the token's own role comes before what RoleExpansion appends")
    void tokenOrderFirst() {
        UserRoleHospitalAssignment doctor = row("ROLE_DOCTOR");
        UserRoleHospitalAssignment surgeon = row("ROLE_SURGEON");
        UserRoleHospitalAssignmentRepository repo = mock(UserRoleHospitalAssignmentRepository.class);
        when(repo.findAllByUser_IdAndHospital_IdAndActiveTrue(USER, HOSPITAL)).thenReturn(List.of(doctor, surgeon));

        assertThat(ActingAssignments.resolve(repo, USER, HOSPITAL, token("ROLE_SURGEON", "ROLE_DOCTOR")))
            .containsSame(surgeon);
    }

    @Test
    @DisplayName("no row of the acting role: the hospital is kept, the role filed is the token's")
    void noMatchKeepsHospitalFilesTokenRole() {
        UserRoleHospitalAssignment doctor = row("ROLE_DOCTOR");
        UserRoleHospitalAssignmentRepository repo = mock(UserRoleHospitalAssignmentRepository.class);
        when(repo.findAllByUser_IdAndHospital_IdAndActiveTrue(USER, HOSPITAL)).thenReturn(List.of(doctor));
        var auth = token("ROLE_SUPER_ADMIN");

        var picked = ActingAssignments.resolve(repo, USER, HOSPITAL, auth).orElseThrow();

        assertThat(picked).isSameAs(doctor);
        assertThat(ActingAssignments.roleName(picked, ActingAssignments.actingRoles(auth))).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("an expanded super-admin token keeps its own role when only an inherited role is held locally")
    void inheritedAssignmentDoesNotReplaceActingRole() {
        UserRoleHospitalAssignment doctor = row("ROLE_DOCTOR");
        UserRoleHospitalAssignmentRepository repo = mock(UserRoleHospitalAssignmentRepository.class);
        when(repo.findAllByUser_IdAndHospital_IdAndActiveTrue(USER, HOSPITAL)).thenReturn(List.of(doctor));
        var auth = token(com.example.hms.security.RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN"))
            .toArray(String[]::new));

        var picked = ActingAssignments.resolve(repo, USER, HOSPITAL, auth).orElseThrow();

        assertThat(picked).isSameAs(doctor);
        assertThat(ActingAssignments.roleName(picked, ActingAssignments.actingRoles(auth))).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("no hospital or no rows: nothing; no acting role: the row's own")
    void edges() {
        UserRoleHospitalAssignmentRepository repo = mock(UserRoleHospitalAssignmentRepository.class);
        when(repo.findAllByUser_IdAndHospital_IdAndActiveTrue(USER, HOSPITAL)).thenReturn(List.of());
        assertThat(ActingAssignments.resolve(repo, USER, null, token("ROLE_DOCTOR"))).isEmpty();
        assertThat(ActingAssignments.resolve(repo, USER, HOSPITAL, token("ROLE_DOCTOR"))).isEmpty();
        assertThat(ActingAssignments.roleName(row("ROLE_NURSE"), List.of())).isEqualTo("NURSE");
        assertThat(ActingAssignments.roleName(null, List.of())).isNull();
    }
}
