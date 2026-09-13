package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
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
 * An encounter keeps its attending's assignment reference after that assignment row is
 * hard-deleted (user removal, {@code DELETE /assignments/..}): on Postgres
 * {@code clinical.encounters.assignment_id} carries no foreign key, because no migration
 * ever created the {@code fk_encounter_assignment} the entity declares. Updating such an
 * encounter must therefore never load the assignment: {@code Encounter.validate()} runs on
 * every update and used to dereference the proxy, so every edit of those encounters failed
 * (dev, 2026-09-13: four Hospital B encounters, one revoked doctor).
 *
 * <p>H2 builds the foreign key from the entity, and dropping it is DDL that would commit the
 * test's transaction and leak rows into sibling tests, so the row is not deleted here. The
 * assertion is the one that matters: after the update flushes, the assignment proxy is still
 * uninitialized, so a missing row behind it cannot have been touched.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class EncounterDanglingAssignmentIT {

    @Autowired
    private TestEntityManager em;

    @Test
    void updatingAnEncounterDoesNotLoadTheAssignmentItCarriesOver() {
        Organization organization = em.persist(Organization.builder()
            .name("Dangling Org").code("ORG-DE1").type(OrganizationType.HOSPITAL_CHAIN).build());
        Hospital hospital = em.persist(Hospital.builder()
            .name("Hospital B").code("HOSP-DE1").address("1 Rue").city("Ouagadougou").country("BF")
            .organization(organization).build());
        User patientUser = em.persist(User.builder()
            .username("de_patient_1").passwordHash("h").email("de_patient_1@example.com")
            .phoneNumber("+22670000921").firstName("Patient").lastName("One").build());
        Patient patient = em.persist(Patient.builder()
            .firstName("Patient").lastName("One").dateOfBirth(LocalDate.of(1998, 1, 8)).gender("MALE")
            .address("1 Rue").phoneNumberPrimary("+22670000921").email("de_patient_1@example.com")
            .user(patientUser).hospitalId(hospital.getId()).organizationId(organization.getId())
            .active(true).build());
        User doctorUser = em.persist(User.builder()
            .username("de_doctor_b").passwordHash("h").email("de_doctor_b@example.com")
            .phoneNumber("+22670000922").firstName("DoctorBF").lastName("DoctorBL").build());
        Role role = em.persist(Role.builder().name("ROLE_DE_DOCTOR").code("ROLE_DE_DOCTOR").build());
        UserRoleHospitalAssignment assignment = em.persist(UserRoleHospitalAssignment.builder()
            .user(doctorUser).role(role).hospital(hospital).assignedAt(LocalDateTime.now()).active(true).build());
        Staff doctor = em.persist(Staff.builder()
            .user(doctorUser).hospital(hospital).assignment(assignment).name("Dr. B")
            .jobTitle(JobTitle.DOCTOR).employmentType(EmploymentType.FULL_TIME).build());
        Encounter encounter = em.persist(Encounter.builder()
            .patient(patient).staff(doctor).hospital(hospital).assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.of(2026, 8, 27, 0, 0))
            .status(EncounterStatus.ARRIVED)
            .code("ENC-DE-1")
            .build());
        em.flush();
        em.clear();

        EntityManager entityManager = em.getEntityManager();
        Encounter reloaded = entityManager.find(Encounter.class, encounter.getId());
        assertThat(Hibernate.isInitialized(reloaded.getAssignment()))
            .as("the assignment is a lazy proxy on load")
            .isFalse();

        reloaded.setNotes("ghftghh");
        em.flush();

        assertThat(Hibernate.isInitialized(reloaded.getAssignment()))
            .as("@PreUpdate validation must not load the assignment it carries over")
            .isFalse();
        em.clear();
        assertThat(entityManager.find(Encounter.class, encounter.getId()).getNotes()).isEqualTo("ghftghh");
    }
}
