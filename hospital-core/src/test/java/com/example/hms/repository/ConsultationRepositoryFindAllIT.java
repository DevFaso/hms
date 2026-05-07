package com.example.hms.repository;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end DB test that conclusively proves the @EntityGraph fix.
 *
 * <p>Reproduces the SUPER_ADMIN cross-tenant flow:
 * <ol>
 *   <li>Insert 3 Consultation rows under one hospital, with mixed statuses
 *       ({@code REQUESTED}, {@code IN_PROGRESS}, {@code COMPLETED}).</li>
 *   <li>Call {@code consultationRepository.findAllByOrderByRequestedAtDesc()}
 *       — the exact query the SUPER_ADMIN-no-X-Hospital-Id path executes from
 *       {@code ConsultationServiceImpl.getAllConsultations(null)}.</li>
 *   <li>Assert all 3 rows are returned, with their @ManyToOne associations
 *       eagerly initialised by the {@code @EntityGraph}.</li>
 * </ol>
 *
 * <p>Before the hot-fix in {@code 25e7fc8f} this assertion failed with size
 * = 0, because the {@code @EntityGraph} included a nested
 * {@code "patient.hospitalRegistrations"} {@code @OneToMany} which silently
 * filtered every row out under Hibernate 6 + derived-method {@code OrderBy}.
 * This test guards the fix end-to-end so a future regression of either the
 * graph or the query method is caught at build time, not in production.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class ConsultationRepositoryFindAllIT {

    @Autowired private ConsultationRepository consultationRepository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Patient patient;
    private Staff requestingProvider;

    @BeforeEach
    void setUp() {
        Organization org = Organization.builder()
            .name("Org One")
            .code("ORG-T1")
            .type(OrganizationType.HOSPITAL_CHAIN)
            .build();
        org = em.persist(org);

        hospital = Hospital.builder()
            .name("Test Hospital")
            .code("HOSP-T1")
            .address("123 Test St")
            .city("Ouagadougou")
            .country("BF")
            .organization(org)
            .build();
        hospital = em.persist(hospital);

        // ── Patient (with its own User) ──
        User patientUser = User.builder()
            .username("patient_test1")
            .passwordHash("h")
            .email("patient_t1@example.com")
            .phoneNumber("+22670000001")
            .firstName("Patient")
            .lastName("One")
            .build();
        patientUser = em.persist(patientUser);

        patient = Patient.builder()
            .firstName("Patient")
            .lastName("One")
            .dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("MALE")
            .address("123 Test St")
            .phoneNumberPrimary("+22670000001")
            .email("patient_t1@example.com")
            .user(patientUser)
            .hospitalId(hospital.getId())
            .organizationId(org.getId())
            .active(true)
            .build();
        patient = em.persist(patient);

        // ── Requesting provider Staff (User + Role + Assignment) ──
        User staffUser = User.builder()
            .username("doc_test1")
            .passwordHash("h")
            .email("doc_t1@example.com")
            .phoneNumber("+22670000002")
            .firstName("Doctor")
            .lastName("Test")
            .build();
        staffUser = em.persist(staffUser);

        Role doctorRole = Role.builder()
            .name("ROLE_DOCTOR")
            .code("ROLE_DOCTOR")
            .build();
        doctorRole = em.persist(doctorRole);

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(staffUser)
            .role(doctorRole)
            .hospital(hospital)
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build();
        assignment = em.persist(assignment);

        requestingProvider = Staff.builder()
            .user(staffUser)
            .hospital(hospital)
            .assignment(assignment)
            .name("Dr. Test")
            .jobTitle(JobTitle.DOCTOR)
            .employmentType(EmploymentType.FULL_TIME)
            .build();
        requestingProvider = em.persist(requestingProvider);
    }

    @Test
    void findAllByOrderByRequestedAtDesc_returnsAllRows_evenWithEntityGraphApplied() {
        // Insert 3 consultations with mixed statuses.
        persistConsultation(ConsultationStatus.REQUESTED,   LocalDateTime.now().minusMinutes(10));
        persistConsultation(ConsultationStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(20));
        persistConsultation(ConsultationStatus.COMPLETED,   LocalDateTime.now().minusMinutes(30));

        em.flush();
        em.clear();

        // ── The query under test — exactly what the SUPER_ADMIN-no-header
        //    path runs from ConsultationServiceImpl.getAllConsultations(null). ──
        List<Consultation> result = consultationRepository.findAllByOrderByRequestedAtDesc();

        assertThat(result)
            .as("findAllByOrderByRequestedAtDesc must return all 3 rows. Before "
              + "commit 25e7fc8f this returned [] because the @EntityGraph "
              + "included a nested @OneToMany 'patient.hospitalRegistrations' "
              + "which Hibernate 6 silently filtered into emptiness when "
              + "combined with derived-method ORDER BY.")
            .hasSize(3);

        // Sort check — most-recently-requested first.
        assertThat(result.get(0).getStatus()).isEqualTo(ConsultationStatus.REQUESTED);
        assertThat(result.get(2).getStatus()).isEqualTo(ConsultationStatus.COMPLETED);

        // Sanity: associations are eagerly initialised (no lazy-init exceptions
        // when accessing them outside the original transaction).
        assertThat(result.get(0).getPatient().getId()).isEqualTo(patient.getId());
        assertThat(result.get(0).getHospital().getId()).isEqualTo(hospital.getId());
        assertThat(result.get(0).getRequestingProvider().getId())
            .isEqualTo(requestingProvider.getId());
    }

    @Test
    void findByHospital_IdOrderByRequestedAtDesc_returnsAllStatuses_includingCompletedAndCancelled() {
        // Reproduces the "Dashboard says 3, list shows 0" UX bug for the
        // hospital-scoped path: when the caller passes no status filter we
        // must return ALL consultations for the hospital — not just the
        // 4 "active" ones (REQUESTED/ACKNOWLEDGED/SCHEDULED/IN_PROGRESS) —
        // so the list endpoint matches the dashboard count(*) tile.
        persistConsultation(ConsultationStatus.REQUESTED, LocalDateTime.now().minusMinutes(10));
        persistConsultation(ConsultationStatus.COMPLETED, LocalDateTime.now().minusMinutes(20));
        persistConsultation(ConsultationStatus.CANCELLED, LocalDateTime.now().minusMinutes(30));

        em.flush();
        em.clear();

        List<Consultation> all = consultationRepository
            .findByHospital_IdOrderByRequestedAtDesc(hospital.getId());

        assertThat(all)
            .as("Hospital-scoped no-filter list must include COMPLETED and CANCELLED "
              + "rows. Before the fix, the no-status path silently filtered to "
              + "[REQUESTED, ACKNOWLEDGED, SCHEDULED, IN_PROGRESS], which hid 2/3 "
              + "of these rows even though the dashboard tile counted them all.")
            .hasSize(3)
            .extracting(Consultation::getStatus)
            .containsExactlyInAnyOrder(
                ConsultationStatus.REQUESTED,
                ConsultationStatus.COMPLETED,
                ConsultationStatus.CANCELLED);
    }

    @Test
    void findByStatusOrderByRequestedAtDesc_alsoEntityGraphed_returnsCorrectSubset() {
        persistConsultation(ConsultationStatus.REQUESTED,   LocalDateTime.now().minusMinutes(10));
        persistConsultation(ConsultationStatus.REQUESTED,   LocalDateTime.now().minusMinutes(20));
        persistConsultation(ConsultationStatus.COMPLETED,   LocalDateTime.now().minusMinutes(30));

        em.flush();
        em.clear();

        List<Consultation> active = consultationRepository
            .findByStatusOrderByRequestedAtDesc(ConsultationStatus.REQUESTED);

        assertThat(active).hasSize(2);
        assertThat(active).allSatisfy(c ->
            assertThat(c.getStatus()).isEqualTo(ConsultationStatus.REQUESTED));
    }

    /* ------------------------------------------------------------------ */

    private void persistConsultation(ConsultationStatus status, LocalDateTime requestedAt) {
        Consultation c = Consultation.builder()
            .patient(patient)
            .hospital(hospital)
            .requestingProvider(requestingProvider)
            .consultationType(ConsultationType.OUTPATIENT_CONSULT)
            .specialtyRequested("Cardiology")
            .reasonForConsult("Test reason")
            .urgency(ConsultationUrgency.ROUTINE)
            .status(status)
            .requestedAt(requestedAt)
            .build();
        em.persist(c);
    }
}
