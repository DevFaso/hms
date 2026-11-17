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
 * QA D791 on H2: DOCTOR_A wrote prescriptions for PATIENT_A2 outside any
 * visit; each hangs on an anchor encounter ({@link Encounter#PRESCRIPTION_ANCHOR_NOTE}).
 * The chart review ("Revue de dossier") read "4 CONSULTATIONS" while the
 * Consultations tab said "Aucune consultation". Every visit query built on
 * {@link EncounterRepository#NOT_PRESCRIPTION_ANCHOR} leaves the anchors out
 * and keeps the visits, a NULL note included; the paged reads give the page's
 * rows to visits, and the counts agree.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("Visit queries leave prescription anchors out (QA D791)")
class EncounterVisitQueriesRepositoryTest {

    @Autowired private EncounterRepository encounterRepository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Patient patient;
    private Staff doctorStaff;
    private UserRoleHospitalAssignment doctorAssignment;
    private Encounter visitWithoutNote;
    private Encounter visitWithNote;
    private final LocalDateTime now = LocalDateTime.now().withNano(0);
    private int sequence;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Anchor Network").code("ORG-AN").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(Hospital.builder()
                .name("Anchor Hospital").code("HAN1")
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+22655501").email("han1@hospital.test")
                .organization(organization).build());
        Role doctorRole = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build());
        User doctor = em.persist(user("doctor"));
        doctorAssignment = em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-AN").description("Doctor assignment")
                .user(doctor).hospital(hospital).role(doctorRole)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        doctorStaff = em.persist(Staff.builder()
                .user(doctor).hospital(hospital).assignment(doctorAssignment)
                .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-AN").name("Dr. Anchor").active(true).build());
        User patientUser = em.persist(user("patient"));
        patient = em.persist(Patient.builder()
                .firstName("Awa").lastName("Ouedraogo").dateOfBirth(LocalDate.of(1990, 5, 2)).gender("F")
                .address("Patient address").city("Ouagadougou").country("Burkina Faso")
                .phoneNumberPrimary("+22678000002").email("awa@patient.test")
                .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());

        // Four prescriptions written outside any visit, newest first: they would
        // fill a page of two before any visit if they were not left out.
        for (int i = 0; i < 4; i++) {
            encounter(Encounter.PRESCRIPTION_ANCHOR_NOTE, now.minusMinutes(i));
        }
        visitWithoutNote = encounter(null, now.minusHours(2));
        visitWithNote = encounter("Douleur abdominale", now.minusHours(3));
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("the chart review's patient read: the visits only, and they get the page's rows")
    void chartReviewPatientRead() {
        Page<Encounter> page = encounterRepository.findVisitsByPatientId(patient.getId(), PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(Encounter::getId)
            .containsExactly(visitWithoutNote.getId(), visitWithNote.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("the chart review's cross-hospital read applies the same rule")
    void chartReviewReadableHospitalsRead() {
        Page<Encounter> page = encounterRepository.findVisitsByPatientIdAndHospitalIdIn(
            patient.getId(), Set.of(hospital.getId()), PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(Encounter::getId)
            .containsExactly(visitWithoutNote.getId(), visitWithNote.getId());
    }

    @Test
    @DisplayName("the raw patient read still returns the anchors (the prescription and FHIR paths need them)")
    void rawReadKeepsTheAnchors() {
        assertThat(encounterRepository.findByPatient_Id(patient.getId())).hasSize(6);
    }

    @Test
    @DisplayName("the dashboard counts count visits")
    void counts() {
        assertThat(encounterRepository.countVisitsByHospitalId(hospital.getId())).isEqualTo(2);
        assertThat(encounterRepository.countVisits()).isEqualTo(2);
        assertThat(encounterRepository.countByHospital_Id(hospital.getId())).isEqualTo(6);
    }

    @Test
    @DisplayName("the encounter report, the tracker board and the day's walk-ins leave the anchors out")
    void dayReads() {
        LocalDateTime from = now.minusDays(1);
        LocalDateTime to = now.plusDays(1);

        assertThat(encounterRepository.findVisitsByHospitalAndDateBetween(hospital.getId(), from, to, PageRequest.of(0, 50))
            .getContent()).extracting(Encounter::getId)
            .containsExactlyInAnyOrder(visitWithoutNote.getId(), visitWithNote.getId());
        assertThat(encounterRepository.findAllByHospitalAndDateRange(hospital.getId(), from, to))
            .extracting(Encounter::getId)
            .containsExactlyInAnyOrder(visitWithoutNote.getId(), visitWithNote.getId());
        assertThat(encounterRepository.findWalkInsForHospitalAndPeriod(hospital.getId(), from, to))
            .extracting(Encounter::getId)
            .containsExactlyInAnyOrder(visitWithoutNote.getId(), visitWithNote.getId());
    }

    private Encounter encounter(String notes, LocalDateTime at) {
        sequence++;
        return em.persist(Encounter.builder()
                .patient(patient).staff(doctorStaff).hospital(hospital).assignment(doctorAssignment)
                .encounterType(EncounterType.CONSULTATION).encounterDate(at)
                .status(EncounterStatus.COMPLETED).notes(notes)
                .code("ENC-AN-" + sequence + "-" + UUID.randomUUID().toString().substring(0, 6)).build());
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-an").passwordHash("hashed-password")
                .email(prefix + "@anchor.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22676" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }
}
