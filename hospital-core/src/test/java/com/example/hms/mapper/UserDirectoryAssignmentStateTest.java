package com.example.hms.mapper;

import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UserDirectoryAssignmentStateTest {
    private final UserMapper mapper = new UserMapper();

    @Test
    void retiredStaffRolesAreNotAdvertisedAsActiveButTheAccountIsRetained() {
        User user = user();
        var nurse = assignment("ROLE_NURSE", false);
        var midwife = assignment("ROLE_MIDWIFE", false);
        user.addRole(nurse.getRole());
        user.addRole(midwife.getRole());
        var row = mapper.toSummaryDTO(user, List.of(nurse, midwife));
        assertThat(row.isActive()).as("account status is independent of hospital grants").isTrue();
        assertThat(row.getAccessActive()).isFalse();
        assertThat(row.getRoleCount()).isZero();
        assertThat(row.getRoleName()).isNull();
        assertThat(row.getReactivationAssignmentId()).isNull();
        assertThat(row.getReactivationOptions()).extracting("assignmentId")
            .containsExactlyInAnyOrder(nurse.getId(), midwife.getId());
    }

    @Test
    void onlyTheVisibleActiveGrantDeterminesTheRoleLabel() {
        User user = user();
        var doctor = assignment("ROLE_DOCTOR", false);
        var nurse = assignment("ROLE_NURSE", true);
        user.addRole(doctor.getRole());
        user.addRole(nurse.getRole());
        var row = mapper.toSummaryDTO(user, List.of(doctor, nurse));
        assertThat(row.getAccessActive()).isTrue();
        assertThat(row.getRoleName()).isEqualTo("ROLE_NURSE");
        assertThat(row.getRoleCount()).isEqualTo(1);
        assertThat(row.getReactivationAssignmentId()).isNull();
    }

    @Test
    void anAccountDisabledDespiteAnActiveGrantStillReadsDisabled() {
        User user = user();
        user.setActive(false);
        var row = mapper.toSummaryDTO(user, List.of(assignment("ROLE_NURSE", true)));
        assertThat(row.isActive()).isFalse();
        assertThat(row.getAccessActive()).isTrue();
    }

    @Test
    void emptyScopesExposeNoGlobalOrRetiredRoleAndPatientActivationHasNoAdminShortcut() {
        User user = user();
        user.addRole(assignment("ROLE_DOCTOR", true).getRole());
        var row = mapper.toSummaryDTO(user, List.of());
        assertThat(row.getAccessActive()).isFalse();
        assertThat(row.getRoleName()).isNull();
        assertThat(row.getReactivationAssignmentId()).isNull();
        assertThat(mapper.toSummaryDTO(user, List.of(assignment("ROLE_PATIENT", false)))
            .getReactivationAssignmentId()).isNull();
        assertThat(mapper.toSummaryDTO(null, List.of())).isNull();
    }

    @Test
    void recoveryNeverChoosesARetiredRoleByUuidAndDoesNotExposeOutOfScopeHistory() {
        User user = user();
        var local = assignment("ROLE_NURSE", false);
        var other = assignment("ROLE_MIDWIFE", false);
        user.addRole(other.getRole());
        var scoped = mapper.toSummaryDTO(user, List.of(local));
        assertThat(scoped.getReactivationAssignmentId()).isEqualTo(local.getId());
        assertThat(scoped.getReactivationOptions()).hasSize(1);
        assertThat(scoped.getReactivationOptions().getFirst().hospitalName()).isEqualTo(local.getHospital().getName());
        var all = mapper.toSummaryDTO(user, List.of(local, other));
        assertThat(all.getReactivationAssignmentId()).isNull();
        assertThat(all.getReactivationOptions()).extracting("roleCode").containsExactlyInAnyOrder("ROLE_NURSE", "ROLE_MIDWIFE");
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setActive(true);
        return user;
    }

    private static UserRoleHospitalAssignment assignment(String code, boolean active) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(code);
        role.setName(code);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setRole(role);
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Synthetic hospital " + code);
        assignment.setHospital(hospital);
        assignment.setActive(active);
        return assignment;
    }
}
