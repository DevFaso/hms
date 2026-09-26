package com.example.hms.repository;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LabResultRepository.findPatientResultsReadableAt} against a real
 * database: the query every patient-wide lab read now goes through, in place
 * of the unscoped {@code findByLabOrder_Patient_Id} pair that loaded every
 * hospital's rows for the callers to discard in memory.
 *
 * <p>The service tests stub this query, and every caller's own filter can only
 * REMOVE rows, so a query that silently dropped the rows a laboratory
 * performed for another hospital (an inner join on the performing hospital,
 * which is null on most orders; a mistyped path) would pass all of them. This
 * pins the three kinds of readable row, the ones that must not come back, and
 * that a page is filled with readable rows rather than cut before filtering.
 */
@DataJpaTest
@ActiveProfiles("test")
// Exactly PatientRepositoryTenantScopeTest's (and LabResultTrendReadableQueryTest's)
// configuration, so they share one cached context: one more
// EntityManagerFactory in the test JVM is enough to exhaust the capped heap
// under the full suite.
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class LabResultPatientReadableQueryTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "resultDate");

    @Autowired private LabResultRepository labResultRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Hospital hospitalA;
    private Hospital hospitalB;
    private Hospital hospitalT;
    private Role role;
    private Patient patient;
    private Patient otherPatient;
    private LabTestDefinition hemoglobin;
    private LabTestDefinition glucose;
    private Staff atA;

    private LabResult orderedAtB;
    private LabResult performedAtBForA;
    private LabResult orderedAtT;
    private LabResult orderedAndRunAtA;
    private LabResult otherTestAtB;
    private LabResult otherPatientAtB;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospitalA = hospitalRepository.save(Hospital.builder().name("Hopital A").code("PRA" + nextId()).build());
        hospitalB = hospitalRepository.save(Hospital.builder().name("Hopital B").code("PRB" + nextId()).build());
        hospitalT = hospitalRepository.save(Hospital.builder().name("Hopital T").code("PRT" + nextId()).build());
        role = roleRepository.save(Role.builder()
            .name("Doctor " + nextId()).code("ROLE_PATIENT_LABS_DOCTOR_" + nextId()).description("ordering").build());

        patient = patient("Awa");
        otherPatient = patient("Issa");
        hemoglobin = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("HGB" + nextId()).name("Hemoglobin").unit("g/dL").build());
        glucose = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("GLU" + nextId()).name("Glucose").unit("mmol/L").build());

        atA = staffAt(hospitalA);
        Staff atB = staffAt(hospitalB);
        Staff atT = staffAt(hospitalT);

        orderedAtB = result(order(patient, hemoglobin, atB, null), 1);
        performedAtBForA = result(order(patient, hemoglobin, atA, hospitalB), 2);
        orderedAtT = result(order(patient, hemoglobin, atT, null), 3);
        orderedAndRunAtA = result(order(patient, hemoglobin, atA, null), 4);
        otherTestAtB = result(order(patient, glucose, atB, null), 5);
        otherPatientAtB = result(order(otherPatient, hemoglobin, atB, null), 6);
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("B reads every test it ordered and what its laboratory performed, and nothing of A's own")
    void orderedHereAndPerformedHere() {
        List<LabResult> rows = labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, Pageable.unpaged());

        assertThat(rows).extracting(LabResult::getId)
            .containsExactlyInAnyOrder(orderedAtB.getId(), performedAtBForA.getId(), otherTestAtB.getId());
    }

    @Test
    @DisplayName("a treatment relationship to T adds T's rows, and the page's sort is honoured")
    void treatmentRelationship() {
        List<LabResult> rows = labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId(), hospitalT.getId()), hospitalB.getId(), false,
            PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(rows).extracting(LabResult::getId).containsExactly(
            orderedAtB.getId(), performedAtBForA.getId(), orderedAtT.getId(), otherTestAtB.getId());
    }

    @Test
    @DisplayName("with no acting hospital the performed clause is off: exactly the orders placed in the set")
    void noActingHospitalMeansNoPerformedClause() {
        // The timeline's and the doctor record's call: B with no performer
        // reads what B ordered, and NOT what B's laboratory ran for A.
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId()), null, false, Pageable.unpaged()))
            .extracting(LabResult::getId)
            .containsExactlyInAnyOrder(orderedAtB.getId(), otherTestAtB.getId());
        // The same call with the readable set widened to T adds T's orders.
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId(), hospitalT.getId()), null, false, Pageable.unpaged()))
            .extracting(LabResult::getId)
            .containsExactlyInAnyOrder(orderedAtB.getId(), otherTestAtB.getId(), orderedAtT.getId());
        // A reads its own orders, including the one it sent to B's laboratory.
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalA.getId()), null, false, Pageable.unpaged()))
            .extracting(LabResult::getId)
            .containsExactlyInAnyOrder(performedAtBForA.getId(), orderedAndRunAtA.getId());
    }

    @Test
    @DisplayName("the performing hospital is not a way into anybody else's orders")
    void aHospitalSeesNothingItNeitherOrderedNorRan() {
        UUID elsewhere = hospitalRepository.save(Hospital.builder().name("Hopital C").code("PRC" + nextId()).build())
            .getId();

        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(elsewhere), elsewhere, false, Pageable.unpaged())).isEmpty();
    }

    @Test
    @DisplayName("a page is filled with readable rows, however many newer unreadable ones there are")
    void aPageIsFullOfReadableRows() {
        // Three results newer than anything B may read. Paging the patient's
        // rows first and filtering after would return these three as the first
        // page and then throw them all away: an empty page, with readable rows
        // right behind it.
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        for (int hour = 1; hour <= 3; hour++) {
            resultHoursAgo(order(patient, hemoglobin, atA, null), hour);
        }
        HospitalContextHolder.clear();

        List<LabResult> rows = labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, PageRequest.of(0, 2, NEWEST_FIRST));

        assertThat(rows).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId(), performedAtBForA.getId());
    }

    @Test
    @DisplayName("findAllPatientResults reads every hospital's rows, but still only this patient's")
    void allPatientResults() {
        List<LabResult> rows = labResultRepository.findAllPatientResults(patient.getId(),
            PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(rows).extracting(LabResult::getId).containsExactly(
            orderedAtB.getId(), performedAtBForA.getId(), orderedAtT.getId(), orderedAndRunAtA.getId(),
            otherTestAtB.getId());
        assertThat(rows).extracting(LabResult::getId).doesNotContain(otherPatientAtB.getId());
    }

    @Test
    @DisplayName("id breaks a resultDate tie, so a page boundary is the same on every read")
    void resultDateTiesAreBrokenById() {
        // Four rows at one instant, read two at a time: the two pages are
        // disjoint and together hold all four, in id order. Without the id
        // key the database may put a tied row on either page, on either read.
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        LocalDateTime instant = LocalDateTime.now().minusMinutes(5).withNano(0);
        List<UUID> tied = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tied.add(labResultRepository.save(LabResult.builder()
                .labOrder(order(patient, hemoglobin, atA, null))
                .actorType(ActorType.SYSTEM)
                .actorLabel("MLLP:PATIENT/FIXTURE")
                .resultValue("3" + i)
                .resultDate(instant)
                .build()).getId());
        }
        HospitalContextHolder.clear();
        Sort withTieBreak = Sort.by(Sort.Direction.DESC, "resultDate", "id");

        List<UUID> first = labResultRepository.findAllPatientResults(patient.getId(),
            PageRequest.of(0, 2, withTieBreak)).stream().map(LabResult::getId).toList();
        List<UUID> second = labResultRepository.findAllPatientResults(patient.getId(),
            PageRequest.of(1, 2, withTieBreak)).stream().map(LabResult::getId).toList();

        // The database orders a uuid by its bytes, unsigned, which is the
        // order of its lower-case hex text (UUID.compareTo is signed).
        List<UUID> expected = tied.stream()
            .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(first).containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(second).containsExactlyElementsOf(expected.subList(2, 4));
    }

    @Test
    @DisplayName("an unknown patient reads as nothing, like a patient with nothing readable")
    void unknownPatient() {
        assertThat(labResultRepository.findPatientResultsReadableAt(UUID.randomUUID(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, Pageable.unpaged())).isEmpty();
    }

    private LabResult resultHoursAgo(LabOrder order, int hoursAgo) {
        return labResultRepository.save(LabResult.builder()
            .labOrder(order)
            .actorType(ActorType.SYSTEM)
            .actorLabel("MLLP:PATIENT/FIXTURE")
            .resultValue(String.valueOf(20 + hoursAgo))
            .resultDate(LocalDateTime.now().minusHours(hoursAgo))
            .build());
    }

    private Patient patient(String firstName) {
        String suffix = nextId();
        User user = userRepository.save(user("pt"));
        return patientRepository.save(Patient.builder()
            .firstName(firstName)
            .lastName("Labs" + suffix)
            .dateOfBirth(LocalDate.of(1990, 3, 15))
            .phoneNumberPrimary("+22671" + suffix)
            .user(user)
            .hospitalId(hospitalA.getId())
            .build());
    }

    private Staff staffAt(Hospital hospital) {
        User user = userRepository.save(user("st"));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return staffRepository.save(Staff.builder()
            .user(user)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(JobTitle.SURGEON)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr " + user.getLastName())
            .active(true)
            .build());
    }

    private LabOrder order(Patient who, LabTestDefinition test, Staff orderingStaff, Hospital performing) {
        return labOrderRepository.save(LabOrder.builder()
            .patient(who)
            .orderingStaff(orderingStaff)
            .labTestDefinition(test)
            .hospital(orderingStaff.getHospital())
            .assignment(orderingStaff.getAssignment())
            .performingHospital(performing)
            .orderDatetime(LocalDateTime.now().minusDays(30))
            .clinicalIndication("Patient labs fixture")
            .build());
    }

    /** A SYSTEM row, {@code daysAgo} old, so the ORDER BY is deterministic. */
    private LabResult result(LabOrder order, int daysAgo) {
        return labResultRepository.save(LabResult.builder()
            .labOrder(order)
            .actorType(ActorType.SYSTEM)
            .actorLabel("MLLP:PATIENT/FIXTURE")
            .resultValue(String.valueOf(10 + daysAgo))
            .resultDate(LocalDateTime.now().minusDays(daysAgo))
            .build());
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@patient-labs.test")
            .firstName(prefix)
            .lastName("User" + suffix)
            .phoneNumber("+22672" + suffix)
            .isActive(true)
            .build();
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
