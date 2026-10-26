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
import com.example.hms.model.encounter.EncounterNote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D831 on H2 (tables built from the entities): an inpatient stay holds one
 * note row per progress entry. DOCTOR_A's unsigned SOAP note and DOCTOR2_A's
 * J1 note on the same encounter are two rows, each with its own author; the
 * encounter's current note is the later one and the earlier one is still
 * there, unsigned, to be signed by its author.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("One encounter note row per entry (QA D831)")
class EncounterNoteEntriesRepositoryTest {

    @Autowired private EncounterNoteRepository encounterNoteRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Patient patient;
    private Encounter stay;
    private User doctorA;
    private User doctor2A;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Notes Network").code("ORG-NT").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(Hospital.builder()
                .name("Notes Hospital").code("HNT1")
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+22655502").email("hnt1@hospital.test")
                .organization(organization).build());
        Role doctorRole = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build());
        doctorA = em.persist(user("doctora"));
        doctor2A = em.persist(user("doctor2a"));
        UserRoleHospitalAssignment assignment = em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-NT").description("Doctor assignment")
                .user(doctorA).hospital(hospital).role(doctorRole)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        Staff staffA = em.persist(Staff.builder()
                .user(doctorA).hospital(hospital).assignment(assignment)
                .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-NT").name("Dr. A").active(true).build());
        User patientUser = em.persist(user("patient"));
        patient = em.persist(Patient.builder()
                .firstName("Awa").lastName("Ouedraogo").dateOfBirth(LocalDate.of(1990, 5, 2)).gender("F")
                .address("Patient address").city("Ouagadougou").country("Burkina Faso")
                .phoneNumberPrimary("+22678000003").email("awa.notes@patient.test")
                .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());
        stay = em.persist(Encounter.builder()
                .patient(patient).staff(staffA).hospital(hospital).assignment(assignment)
                .encounterType(EncounterType.INPATIENT).encounterDate(LocalDateTime.now().minusDays(1))
                .status(EncounterStatus.IN_PROGRESS).admissionId(UUID.randomUUID())
                .code("ENC-NT-" + UUID.randomUUID().toString().substring(0, 6)).build());
        em.flush();
    }

    @Test
    @DisplayName("the second doctor's entry is a second row; the first doctor's draft keeps its author and text")
    void twoAuthorsTwoRows() throws InterruptedException {
        EncounterNote draftA = em.persist(entry(doctorA, "QA R13 J0 toux fébrile"));
        em.flush();
        Thread.sleep(20);
        EncounterNote entry2 = em.persist(entry(doctor2A, "QA R13 J1 apyrétique"));
        em.flush();
        em.clear();

        List<EncounterNote> entries = encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(stay.getId());
        assertThat(entries).extracting(EncounterNote::getId).containsExactly(draftA.getId(), entry2.getId());
        assertThat(entries.get(0).getAuthor().getId()).isEqualTo(doctorA.getId());
        assertThat(entries.get(0).getSubjective()).isEqualTo("QA R13 J0 toux fébrile");
        assertThat(entries.get(0).getSignedAt()).isNull();
        assertThat(entries.get(1).getAuthor().getId()).isEqualTo(doctor2A.getId());

        assertThat(encounterNoteRepository.findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(stay.getId()))
            .get().extracting(EncounterNote::getId).isEqualTo(entry2.getId());
        assertThat(encounterNoteRepository.existsByEncounter_Id(stay.getId())).isTrue();
    }

    @Test
    @DisplayName("the encounter loads every entry, and its current note is the latest")
    void encounterCarriesEveryEntry() throws InterruptedException {
        EncounterNote draftA = em.persist(entry(doctorA, "J0"));
        em.flush();
        Thread.sleep(20);
        EncounterNote entry2 = em.persist(entry(doctor2A, "J1"));
        em.flush();
        em.clear();

        Encounter loaded = encounterRepository.findById(stay.getId()).orElseThrow();
        assertThat(loaded.getEncounterNotes()).extracting(EncounterNote::getId)
            .containsExactly(draftA.getId(), entry2.getId());
        assertThat(loaded.getEncounterNote().getId()).isEqualTo(entry2.getId());
        assertThat(loaded.getLatestSignedEncounterNote()).isNull();
    }

    @Test
    @DisplayName("the latest SIGNED entry is what a patient-facing summary reads, not a later draft")
    void latestSignedEntry() throws InterruptedException {
        EncounterNote signed = entry(doctorA, "J0");
        signed.setSignedAt(LocalDateTime.now().minusHours(1));
        signed = em.persist(signed);
        em.flush();
        Thread.sleep(20);
        em.persist(entry(doctor2A, "J1 draft"));
        em.flush();
        em.clear();

        Encounter loaded = encounterRepository.findById(stay.getId()).orElseThrow();
        assertThat(loaded.getLatestSignedEncounterNote().getId()).isEqualTo(signed.getId());
    }

    private EncounterNote entry(User author, String subjective) {
        return EncounterNote.builder()
            .encounter(stay).patient(patient).hospital(hospital)
            .author(author).authorName(author.getFirstName())
            .subjective(subjective)
            .documentedAt(LocalDateTime.now())
            .build();
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-nt").passwordHash("hashed-password")
                .email(prefix + "@notes.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22677" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }
}
