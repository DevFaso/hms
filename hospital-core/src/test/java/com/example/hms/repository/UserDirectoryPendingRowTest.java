package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoped directory (GET /users, /users/search) on H2: a pending row
 * (inactive, never confirmed) lists no account, while an active or a
 * confirmed one does, and the scoped role filter follows the same rule.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("User directory: pending rows list nobody")
class UserDirectoryPendingRowTest {

    @Autowired private UserRepository userRepository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private User pending;
    private User confirmedInactive;
    private User active;
    private User doctorInvitedAsNurse;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Directory Network").code("ORG-DIR").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(Hospital.builder()
                .name("Directory Hospital").code("HDIR1")
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+22655500001").email("hdir1@hospital.test")
                .organization(organization).build());
        Role nurse = em.persist(Role.builder().name("Nurse").code("ROLE_NURSE").description("Nurse role").build());
        Role doctor = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build());

        pending = em.persist(user("pending"));
        confirmedInactive = em.persist(user("former"));
        active = em.persist(user("active"));
        row(pending, nurse, false, null);
        row(confirmedInactive, nurse, false, LocalDateTime.now().minusDays(3));
        row(active, nurse, true, null);
        doctorInvitedAsNurse = em.persist(user("doctor"));
        row(doctorInvitedAsNurse, doctor, true, null);
        row(doctorInvitedAsNurse, nurse, false, null);
        em.flush();
        em.clear();
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-dir").passwordHash("hashed-password")
                .email(prefix + "@directory.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22676" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }

    private void row(User user, Role role, boolean isActive, LocalDateTime verifiedAt) {
        UserRoleHospitalAssignment a = UserRoleHospitalAssignment.builder()
                .assignmentCode("DIR-" + user.getUsername() + "-" + role.getCode()).description("Directory row")
                .user(user).hospital(hospital).role(role)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(isActive).build();
        a.setConfirmationVerifiedAt(verifiedAt);
        em.persist(a);
    }

    private Set<UUID> ids(Page<User> page) {
        return Set.copyOf(page.map(User::getId).getContent());
    }

    @Test
    @DisplayName("the scoped list shows the active and the confirmed accounts, not the pending one")
    void scopedListSkipsPendingRows() {
        Page<User> page = userRepository.findAllPaged(false, false, true, Set.of(hospital.getId()),
                PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactlyInAnyOrder(
                active.getId(), confirmedInactive.getId(), doctorInvitedAsNurse.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("a scoped search by role finds no account through a pending row, even a listed one")
    void scopedRoleSearchSkipsPendingRows() {
        Page<User> page = userRepository.searchUsers(null, "ROLE_NURSE", null, false, false, true,
                Set.of(hospital.getId()), PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactlyInAnyOrder(active.getId(), confirmedInactive.getId());
    }

    @Test
    @DisplayName("the unscoped (super-admin) list still shows every account")
    void unscopedListShowsEveryone() {
        Page<User> page = userRepository.findAllPaged(false, false, false, UserRepository.DIRECTORY_UNSCOPED,
                PageRequest.of(0, 20));

        assertThat(ids(page)).contains(pending.getId(), active.getId(), confirmedInactive.getId());
    }
}
