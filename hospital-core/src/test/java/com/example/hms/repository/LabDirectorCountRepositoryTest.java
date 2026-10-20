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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D523: the sole-director count behind the D417 combined review-approval
 * must count only people who can still sign in. A disabled or soft-deleted
 * second director whose assignment was left active must not lock the only
 * working director out.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("countActiveUsersWithRoleCodeAtHospital (D523)")
class LabDirectorCountRepositoryTest {

    private static final String ROLE = "ROLE_LAB_DIRECTOR";

    @Autowired private UserRoleHospitalAssignmentRepository repository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Role role;
    private int seq;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Director Network").code("ORG-LD").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(Hospital.builder()
                .name("Director Hospital").code("HLD1")
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+22655501").email("hld1@hospital.test")
                .organization(organization).build());
        role = em.persist(Role.builder().name("Lab director").code(ROLE).description("Lab director").build());
    }

    private void director(boolean active, boolean deleted) {
        seq++;
        User user = em.persist(User.builder()
                .username("dir" + seq + "-ld").passwordHash("hashed-password")
                .email("dir" + seq + "@director.test").firstName("Dir" + seq).lastName("User")
                .phoneNumber("+2267700" + seq).isActive(active).isDeleted(deleted).build());
        em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-LD" + seq).description("Director assignment")
                .user(user).hospital(hospital).role(role)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        em.flush();
    }

    @Test
    void countsTwoWorkingDirectors() {
        director(true, false);
        director(true, false);
        assertThat(repository.countActiveUsersWithRoleCodeAtHospital(hospital.getId(), ROLE)).isEqualTo(2L);
    }

    @Test
    void disabledSecondDirector_isNotCounted() {
        director(true, false);
        director(false, false);
        assertThat(repository.countActiveUsersWithRoleCodeAtHospital(hospital.getId(), ROLE)).isEqualTo(1L);
    }

    @Test
    void softDeletedSecondDirector_isNotCounted() {
        director(true, false);
        director(true, true);
        assertThat(repository.countActiveUsersWithRoleCodeAtHospital(hospital.getId(), ROLE)).isEqualTo(1L);
    }
}
