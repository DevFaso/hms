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
 * QA D1610: an announcement made at hospital A reached every staff member of every hospital. Its
 * audience is now A's staff: an active, non-patient assignment at A, on an undeleted account, each once.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("announcement audience (D1610)")
class AnnouncementAudienceRepositoryTest {

    @Autowired private UserRoleHospitalAssignmentRepository repository;
    @Autowired private TestEntityManager em;

    private Hospital hospitalA;
    private Hospital hospitalB;
    private Role nurse;
    private Role doctor;
    private Role patient;
    private int seq;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Audience Network").code("ORG-AUD").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospitalA = hospital(organization, "HAUDA", "+22655561");
        hospitalB = hospital(organization, "HAUDB", "+22655562");
        nurse = em.persist(Role.builder().name("Nurse").code("ROLE_NURSE").description("Nurse").build());
        doctor = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor").build());
        patient = em.persist(Role.builder().name("Patient").code("ROLE_PATIENT").description("Patient").build());
    }

    private Hospital hospital(Organization organization, String code, String phone) {
        return em.persist(Hospital.builder()
                .name("Hospital " + code).code(code)
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber(phone)
                .email(code.toLowerCase() + "@hospital.test")
                .organization(organization).build());
    }

    private User user(String name, boolean deleted) {
        seq++;
        return em.persist(User.builder()
                .username(name).passwordHash("hashed-password")
                .email(name + "@audience.test").firstName(name).lastName("User")
                .phoneNumber("+2267800" + seq).isActive(true).isDeleted(deleted).build());
    }

    private void assign(User user, Hospital hospital, Role role, boolean active) {
        seq++;
        em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-AUD" + seq).description("assignment")
                .user(user).hospital(hospital).role(role)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(active).build());
    }

    @Test
    void anAnnouncementAtA_reachesAsStaffOnly_eachOnce() {
        User nurseA = user("nurse-a", false);
        assign(nurseA, hospitalA, nurse, true);
        User doctorA = user("doctor-a", false);
        assign(doctorA, hospitalA, doctor, true);
        assign(doctorA, hospitalA, nurse, true);
        User nurseB = user("nurse-b", false);
        assign(nurseB, hospitalB, nurse, true);
        User patientA = user("patient-a", false);
        assign(patientA, hospitalA, patient, true);
        User formerA = user("former-a", false);
        assign(formerA, hospitalA, nurse, false);
        User deletedA = user("deleted-a", true);
        assign(deletedA, hospitalA, nurse, true);
        em.flush();

        assertThat(repository.findLiveStaffUsersAtHospital(hospitalA.getId()))
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("nurse-a", "doctor-a");
        assertThat(repository.findLiveStaffUsersAtHospital(hospitalB.getId()))
                .extracting(User::getUsername)
                .containsExactly("nurse-b");
    }

    @Test
    void aPlatformWideAnnouncement_reachesEveryHospitalsStaff_butNoPatient() {
        assign(user("nurse-a", false), hospitalA, nurse, true);
        assign(user("nurse-b", false), hospitalB, nurse, true);
        assign(user("patient-b", false), hospitalB, patient, true);
        em.flush();

        assertThat(repository.findLiveStaffUsersPlatformWide())
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("nurse-a", "nurse-b");
    }
}
