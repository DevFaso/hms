package com.example.hms.repository.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PharmacyType;
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
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionQueueClaim;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G13 AC-13 and plan rule 2, on H2 (tables built from the entities): the
 * MINE and UNCLAIMED page queries with their count queries, and the
 * {@code @OnDelete} cascade from a deleted prescription to its claim.
 */
@DataJpaTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("PrescriptionQueueClaim queries (G13)")
class PrescriptionQueueClaimRepositoryTest {

    private static final List<PrescriptionStatus> QUEUE = List.of(PrescriptionStatus.SIGNED,
            PrescriptionStatus.PARTIALLY_FILLED, PrescriptionStatus.TRANSMISSION_FAILED);

    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private PrescriptionQueueClaimRepository claimRepository;
    @Autowired private TestEntityManager em;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 7, 10, 0);
    private final LocalDateTime activeAfter = now.minusMinutes(15);

    private Hospital hospital;
    private Hospital otherHospital;
    private User me;
    private User colleague;
    private Patient patient;
    private Staff doctorStaff;
    private Encounter encounter;
    private UserRoleHospitalAssignment doctorAssignment;
    private Pharmacy dispensary;

    private Prescription mineActive;
    private Prescription mineExpired;
    private Prescription colleagueActive;
    private Prescription unclaimed;
    private Prescription unclaimedButPrepared;
    private Prescription withdrawnMine;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
                .name("Claim Network").code("ORG-QC").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = em.persist(hospital("HQC1", organization));
        otherHospital = em.persist(hospital("HQC2", organization));

        Role doctorRole = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build());
        User doctor = em.persist(user("doctor"));
        doctorAssignment = em.persist(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-QC").description("Doctor assignment")
                .user(doctor).hospital(hospital).role(doctorRole)
                .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        doctorStaff = em.persist(Staff.builder()
                .user(doctor).hospital(hospital).assignment(doctorAssignment)
                .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-QC").name("Dr. Doctor").active(true).build());
        me = em.persist(user("me"));
        colleague = em.persist(user("colleague"));
        User patientUser = em.persist(user("patient"));
        patient = em.persist(Patient.builder()
                .firstName("Aminata").lastName("Diallo").dateOfBirth(LocalDate.of(1992, 3, 10)).gender("F")
                .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
                .phoneNumberPrimary("+22678000001").email("aminata@patient.test")
                .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());
        encounter = em.persist(Encounter.builder()
                .patient(patient).staff(doctorStaff).hospital(hospital).assignment(doctorAssignment)
                .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
                .code("ENC-QC").build());
        dispensary = em.persist(Pharmacy.builder()
                .hospital(hospital).name("Dispensary").pharmacyType(PharmacyType.HOSPITAL_DISPENSARY).build());

        mineActive = rx(PrescriptionStatus.SIGNED);
        mineExpired = rx(PrescriptionStatus.SIGNED);
        colleagueActive = rx(PrescriptionStatus.PARTIALLY_FILLED);
        unclaimed = rx(PrescriptionStatus.TRANSMISSION_FAILED);
        unclaimedButPrepared = rx(PrescriptionStatus.SIGNED);
        withdrawnMine = rx(PrescriptionStatus.CANCELLED);

        claim(mineActive, me, now.minusMinutes(5));
        claim(mineExpired, me, now.minusMinutes(16));
        claim(colleagueActive, colleague, now.minusMinutes(1));
        claim(withdrawnMine, me, now.minusMinutes(2));
        em.persist(Dispense.builder()
                .prescription(unclaimedButPrepared).patient(patient).pharmacy(dispensary)
                .dispensedByUser(me).preparedByUser(me).medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN).quantityDispensed(BigDecimal.TEN)
                .status(DispenseStatus.PENDING).dispensedAt(null).build());
        em.flush();
        em.clear();
    }

    private static Hospital hospital(String code, Organization organization) {
        return Hospital.builder()
                .name("Claim Hospital " + code).code(code)
                .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
                .phoneNumber("+226555" + code.hashCode()).email(code + "@hospital.test")
                .organization(organization).build();
    }

    private static User user(String prefix) {
        return User.builder()
                .username(prefix + "-qc").passwordHash("hashed-password")
                .email(prefix + "@queue-claim.test").firstName(prefix + "FN").lastName("User")
                .phoneNumber("+22677" + Math.abs(prefix.hashCode() % 1000000)).isActive(true).build();
    }

    private Prescription rx(PrescriptionStatus status) {
        return em.persist(Prescription.builder()
                .patient(patient).staff(doctorStaff).encounter(encounter).hospital(hospital)
                .assignment(doctorAssignment).medicationName("Amoxicillin")
                .quantity(BigDecimal.TEN).status(status).build());
    }

    private void claim(Prescription prescription, User by, LocalDateTime at) {
        em.persist(PrescriptionQueueClaim.builder().prescription(prescription).claimedBy(by).claimedAt(at).build());
    }

    private static List<UUID> ids(Page<Prescription> page) {
        return page.getContent().stream().map(Prescription::getId).toList();
    }

    @Test
    @DisplayName("MINE: only the caller's ACTIVE claims on queue statuses at this hospital; the count agrees")
    void mine() {
        Page<Prescription> page = prescriptionRepository.findWorkQueueClaimedBy(hospital.getId(), QUEUE,
                me.getId(), activeAfter, PageRequest.of(0, 20, Sort.by("createdAt")));

        assertThat(ids(page)).containsExactly(mineActive.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("UNCLAIMED: no active claim AND no open preparation; an expired claim counts as unclaimed")
    void unclaimed() {
        Page<Prescription> page = prescriptionRepository.findWorkQueueUnclaimed(hospital.getId(), QUEUE,
                activeAfter, PageRequest.of(0, 20, Sort.by("createdAt")));

        assertThat(ids(page)).containsExactlyInAnyOrder(mineExpired.getId(), unclaimed.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("the count queries carry the same predicate: page size 1 still reports the true totals")
    void countsMatchThePredicate() {
        Page<Prescription> unclaimedPage = prescriptionRepository.findWorkQueueUnclaimed(hospital.getId(), QUEUE,
                activeAfter, PageRequest.of(0, 1, Sort.by("createdAt")));
        Page<Prescription> colleaguePage = prescriptionRepository.findWorkQueueClaimedBy(hospital.getId(), QUEUE,
                colleague.getId(), activeAfter, PageRequest.of(0, 1, Sort.by("createdAt")));

        assertThat(unclaimedPage.getContent()).hasSize(1);
        assertThat(unclaimedPage.getTotalElements()).isEqualTo(2);
        assertThat(unclaimedPage.getTotalPages()).isEqualTo(2);
        assertThat(colleaguePage.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("another hospital's queue sees none of these rows")
    void otherHospital() {
        assertThat(prescriptionRepository.findWorkQueueUnclaimed(otherHospital.getId(), QUEUE, activeAfter,
                PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(prescriptionRepository.findWorkQueueClaimedBy(otherHospital.getId(), QUEUE, me.getId(),
                activeAfter, PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("the page's claims come back in one query with their holder")
    void claimsOfAPage() {
        List<PrescriptionQueueClaim> claims = claimRepository.findByPrescription_IdIn(
                List.of(mineActive.getId(), colleagueActive.getId(), unclaimed.getId()));

        assertThat(claims).hasSize(2);
        assertThat(claims).allSatisfy(c -> assertThat(c.getClaimedBy().getFirstName()).isNotBlank());
    }

    @Test
    @DisplayName("rule 2: deleting a prescription deletes its claim (the @OnDelete cascade)")
    void prescriptionDeleteCascadesToItsClaim() {
        UUID id = mineActive.getId();
        assertThat(claimRepository.findByPrescription_Id(id)).isPresent();

        em.getEntityManager().createNativeQuery("DELETE FROM clinical.prescriptions WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        em.clear();

        assertThat(claimRepository.findByPrescription_Id(id)).isEmpty();
        assertThat(claimRepository.count()).isEqualTo(3);
    }
}
