package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D652 on H2: the paged prescription list read across hospitals leaves out
 * another hospital's never-sent rows (DRAFT, PENDING_SIGNATURE) - the rows its
 * by-id read answers 404 for - and keeps the acting hospital's whatever their
 * status. The count query agrees.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("Prescription list across hospitals hides foreign never-sent rows (QA D652)")
class PrescriptionSentAcrossHospitalsRepositoryTest {

    private static final List<PrescriptionStatus> NEVER_SENT =
        List.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE);

    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private Staff doctorStaff;
    private Encounter encounter;
    private UserRoleHospitalAssignment doctorAssignment;
    private Staff doctorStaffB;
    private Encounter encounterB;
    private UserRoleHospitalAssignment doctorAssignmentB;

    private Prescription draftAtA;
    private Prescription pendingAtA;
    private Prescription signedAtA;
    private Prescription draftAtB;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Sent Network").code("ORG-NS").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(hospital("HNS1", organization));
        otherHospital = em.persist(hospital("HNS2", organization));

        Role doctorRole = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build());
        User doctor = em.persist(user("doctor"));
        doctorAssignment = em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-NS").description("Doctor assignment")
                .user(doctor).hospital(hospital).role(doctorRole)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        doctorStaff = em.persist(Staff.builder()
                .user(doctor).hospital(hospital).assignment(doctorAssignment)
                .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-NS").name("Dr. Doctor").active(true).build());
        User patientUser = em.persist(user("patient"));
        patient = em.persist(Patient.builder()
                .firstName("Aminata").lastName("Diallo").dateOfBirth(LocalDate.of(1992, 3, 10)).gender("F")
                .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
                .phoneNumberPrimary("+22678000001").email("aminata@patient.test")
                .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());
        encounter = em.persist(Encounter.builder()
                .patient(patient).staff(doctorStaff).hospital(hospital).assignment(doctorAssignment)
                .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
                .code("ENC-NS").build());

        // Hospital B's own doctor and encounter: a prescription's staff, assignment
        // and encounter must belong to its hospital.
        User doctorB = em.persist(user("doctorb"));
        doctorAssignmentB = em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-NS-B").description("Doctor assignment B")
                .user(doctorB).hospital(otherHospital).role(doctorRole)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        doctorStaffB = em.persist(Staff.builder()
                .user(doctorB).hospital(otherHospital).assignment(doctorAssignmentB)
                .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-NS-B").name("Dr. Doctor B").active(true).build());
        encounterB = em.persist(Encounter.builder()
                .patient(patient).staff(doctorStaffB).hospital(otherHospital).assignment(doctorAssignmentB)
                .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
                .code("ENC-NS-B").build());

        draftAtA = rx(PrescriptionStatus.DRAFT, hospital);
        pendingAtA = rx(PrescriptionStatus.PENDING_SIGNATURE, hospital);
        signedAtA = rx(PrescriptionStatus.SIGNED, hospital);
        draftAtB = rx(PrescriptionStatus.DRAFT, otherHospital);
        em.flush();
        em.clear();
    }

    private static Hospital hospital(String code, Organization organization) {
        return Hospital.builder()
                .name("Sent Hospital " + code).code(code)
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+226555" + code.hashCode()).email(code + "@hospital.test")
                .organization(organization).build();
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-ns").passwordHash("hashed-password")
                .email(prefix + "@never-sent.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22677" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }

    private Prescription rx(PrescriptionStatus status, Hospital at) {
        boolean atA = at == hospital;
        return em.persist(Prescription.builder()
                .patient(patient).staff(atA ? doctorStaff : doctorStaffB).encounter(atA ? encounter : encounterB)
                .hospital(at).assignment(atA ? doctorAssignment : doctorAssignmentB).medicationName("Amoxicillin")
                .quantity(BigDecimal.TEN).status(status).build());
    }

    private static List<UUID> ids(Page<Prescription> page) {
        return page.getContent().stream().map(Prescription::getId).toList();
    }

    @Test
    @DisplayName("acting at B: A's DRAFT and PENDING_SIGNATURE are left out, A's SIGNED and B's own DRAFT stay")
    void foreignNeverSentRowsAreLeftOut() {
        Page<Prescription> page = prescriptionRepository.findSentAcrossHospitals(patient.getId(),
            Set.of(hospital.getId(), otherHospital.getId()), otherHospital.getId(), NEVER_SENT, PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);
        Page<Prescription> all = prescriptionRepository.findSentAcrossHospitals(patient.getId(),
            Set.of(hospital.getId(), otherHospital.getId()), otherHospital.getId(), NEVER_SENT, PageRequest.of(0, 20));
        assertThat(ids(all)).containsExactlyInAnyOrder(signedAtA.getId(), draftAtB.getId())
            .doesNotContain(draftAtA.getId(), pendingAtA.getId());
    }

    @Test
    @DisplayName("the status-filtered variant applies the same rule")
    void statusFilteredVariant() {
        Page<Prescription> page = prescriptionRepository.findSentAcrossHospitalsAndStatusIn(patient.getId(),
            Set.of(hospital.getId(), otherHospital.getId()), otherHospital.getId(), NEVER_SENT,
            List.of(PrescriptionStatus.DRAFT), PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactly(draftAtB.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }
}
