package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D190: the directory (GET /users) must apply the same rule as
 * UserAccountAccess.canView. A user who moved from hospital A to hospital B
 * (inactive row at A, active row at B) is B's, not listed to A's admin.
 */
@TenantScopedDataJpaTest
@Import(UserRepositoryDirectoryScopeTest.JpaConfig.class)
class UserRepositoryDirectoryScopeTest {

    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private Hospital a;
    private Hospital b;
    private UUID moved;
    private UUID pending;
    private UUID activeAtA;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        a = hospitalRepository.save(Hospital.builder().name("Hopital A").code("DA").build());
        b = hospitalRepository.save(Hospital.builder().name("Hopital B").code("DB").build());
        Role nurse = new Role();
        nurse.setName("ROLE_NURSE_DIR_TEST");
        nurse.setCode("ROLE_NURSE_DIR_TEST");
        nurse = roleRepository.save(nurse);

        User movedUser = user("moved.user");
        assign(movedUser, nurse, a, false);
        assign(movedUser, nurse, b, true);
        moved = movedUser.getId();

        User pendingUser = user("pending.user");
        assign(pendingUser, nurse, a, false);
        pending = pendingUser.getId();

        User activeUser = user("active.user");
        assign(activeUser, nurse, a, true);
        assign(activeUser, nurse, b, true);
        activeAtA = activeUser.getId();
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("hospital A's admin does not list a user now active only at B")
    void movedUserHiddenFromOldHospital() {
        assertThat(listed(Set.of(a.getId()))).contains(pending, activeAtA).doesNotContain(moved);
    }

    @Test
    @DisplayName("hospital B's admin lists the moved user")
    void movedUserListedAtNewHospital() {
        assertThat(listed(Set.of(b.getId()))).contains(moved, activeAtA).doesNotContain(pending);
    }

    @Test
    @DisplayName("an admin of both hospitals lists all three")
    void adminOfBoth() {
        assertThat(listed(Set.of(a.getId(), b.getId()))).contains(moved, pending, activeAtA);
    }

    @Test
    @DisplayName("QA D430: the non-administrator directory lists colleagues, never a patient-only account")
    void staffDirectoryExcludesPatientOnlyAccounts() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        Role patientRole = roleRepository.findByCode("ROLE_PATIENT").orElseGet(() -> {
            Role r = new Role();
            r.setName("ROLE_PATIENT");
            r.setCode("ROLE_PATIENT");
            return roleRepository.save(r);
        });
        User patientOnly = user("patient.only");
        assign(patientOnly, patientRole, a, true);
        Set<UUID> atA = Set.of(a.getId());

        List<UUID> staffListed = userRepository.findStaffDirectoryPaged(true, atA, false, false, PageRequest.of(0, 50))
            .map(User::getId).getContent();
        List<UUID> staffSearched = userRepository.searchStaffDirectory("Test", null, null, null, false, false, false,
            true, atA, PageRequest.of(0, 50)).map(User::getId).getContent();

        assertThat(staffListed).contains(pending, activeAtA).doesNotContain(patientOnly.getId());
        assertThat(staffSearched).contains(pending, activeAtA).doesNotContain(patientOnly.getId());
        // The administrators' all-accounts list still sees the patient.
        assertThat(listed(atA)).contains(patientOnly.getId());
    }

    private List<UUID> listed(Set<UUID> hospitalIds) {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        return userRepository.findAllPaged(false, false, true, hospitalIds, PageRequest.of(0, 50))
            .map(User::getId).getContent();
    }

    private User user(String username) {
        return userRepository.save(User.builder()
            .username(username)
            .passwordHash("hashed-secret")
            .email(username + "@example.com")
            .phoneNumber("+2267" + Math.abs(username.hashCode() % 10000000))
            .firstName("Test")
            .lastName("User")
            .build());
    }

    private void assign(User user, Role role, Hospital hospital, boolean active) {
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setHospital(hospital);
        assignment.setActive(active);
        assignmentRepository.save(assignment);
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.example.hms.repository",
        repositoryBaseClass = TenantAwareJpaRepository.class)
    @EntityScan(basePackageClasses = User.class)
    static class JpaConfig {
    }
}
