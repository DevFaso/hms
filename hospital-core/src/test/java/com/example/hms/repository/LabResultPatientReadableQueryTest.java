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
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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
@TenantScopedDataJpaTest
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
    @Autowired private jakarta.persistence.EntityManager entityManager;

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
        // No performer: B reads what B ordered, and NOT what B's laboratory
        // ran for A. (The staff lab views pass the acting hospital as the
        // performer since #751 was applied to them; see the next test.)
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
    @DisplayName("a staff view at T sees its own orders, not what A ordered and B's laboratory ran")
    void aThirdHospitalDoesNotSeeAnotherPairsOrderAndLaboratory() {
        // The staff lab views' call: T as its own readable set and performer.
        // performedAtBForA was ordered at A and run at B, so T neither ordered
        // it nor ran it; T's own order is all T gets.
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalT.getId()), hospitalT.getId(), false, Pageable.unpaged()))
            .extracting(LabResult::getId)
            .containsExactly(orderedAtT.getId());
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
    @DisplayName("QA D211: a non-laboratory staff list is released-only and honours patientId")
    void staffListForANonLaboratoryReader() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        orderedAtB.setReleased(true);
        labResultRepository.save(orderedAtB);
        otherPatientAtB.setReleased(true);
        labResultRepository.save(otherPatientAtB);
        HospitalContextHolder.clear();
        UUID sentinel = new UUID(0L, 0L);

        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), true, true, sentinel,
            true, sentinel, Pageable.unpaged()).getContent()).extracting(LabResult::getId)
            .containsExactlyInAnyOrder(orderedAtB.getId(), otherPatientAtB.getId());
        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), true, false,
            patient.getId(), true, patient.getId(), Pageable.unpaged()).getContent()).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId());
        // The laboratory still reads its unreleased rows, filtered by patient.
        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), false, false,
            patient.getId(), true, patient.getId(), Pageable.unpaged()).getContent()).extracting(LabResult::getId)
            .containsExactlyInAnyOrder(orderedAtB.getId(), performedAtBForA.getId(), otherTestAtB.getId());
    }

    @Test
    @DisplayName("QA D781: GET /lab-results?labOrderId= lists that order's results only, in both scopes")
    void labOrderFilterNarrowsTheStaffList() {
        UUID sentinel = new UUID(0L, 0L);
        UUID orderId = orderedAtB.getLabOrder().getId();
        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), false, true, sentinel,
            false, orderId, Pageable.unpaged()).getContent()).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId());
        assertThat(labResultRepository.findAllForReader(false, true, sentinel, false, orderId,
            Pageable.unpaged()).getContent()).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId());
        // An unknown order lists nothing rather than the whole hospital.
        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), false, true, sentinel,
            false, UUID.randomUUID(), Pageable.unpaged()).getContent()).isEmpty();
    }

    @Test
    @DisplayName("QA D206: a hand-entered result the server paged as critical counts on the doctor's tile")
    void criticallyNotifiedResultCountsOnTheTile() {
        LocalDateTime floor = LocalDateTime.now().minusDays(7);
        assertThat(labResultRepository.countUnacknowledgedCriticalForOrderingStaff(atA.getId(), floor)).isZero();

        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        orderedAndRunAtA.setCriticalNotifiedAt(LocalDateTime.now());
        labResultRepository.save(orderedAndRunAtA);
        HospitalContextHolder.clear();

        assertThat(labResultRepository.countUnacknowledgedCriticalForOrderingStaff(atA.getId(), floor)).isEqualTo(1);
    }

    @Test
    @DisplayName("QA D601: a refused delete of an ACTIVE definition leaves its released critical result in the chart and the critical list")
    void refusedDefinitionDeleteKeepsReleasedCriticalResult() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hemoglobin.setHospital(hospitalB);
        hemoglobin.setApprovalStatus(com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE);
        labTestDefinitionRepository.save(hemoglobin);
        orderedAtB.setReleased(true);
        orderedAtB.setCriticalNotifiedAt(LocalDateTime.now());
        labResultRepository.saveAndFlush(orderedAtB);
        HospitalContextHolder.clear();

        User manager = User.builder().username("labmgr").build();
        manager.setId(UUID.randomUUID());
        UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
        org.mockito.Mockito.when(users.findByUsername("labmgr")).thenReturn(java.util.Optional.of(manager));
        UserRoleHospitalAssignmentRepository assignments =
            org.mockito.Mockito.mock(UserRoleHospitalAssignmentRepository.class);
        org.mockito.Mockito.when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(
                org.mockito.ArgumentMatchers.eq(manager.getId()), org.mockito.ArgumentMatchers.eq(hospitalB.getId()),
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(true);
        com.example.hms.service.LabTestDefinitionServiceImpl service =
            new com.example.hms.service.LabTestDefinitionServiceImpl(labTestDefinitionRepository,
                org.mockito.Mockito.mock(com.example.hms.mapper.LabTestDefinitionMapper.class), assignments, users,
                hospitalRepository, org.mockito.Mockito.mock(com.example.hms.security.tenant.ActingScopeResolver.class),
                org.mockito.Mockito.mock(com.example.hms.service.AuditEventLogService.class), labOrderRepository);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("labmgr", "n/a",
                List.of()));
        UUID definitionId = hemoglobin.getId();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(definitionId))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertThat(labTestDefinitionRepository.findById(definitionId)).isPresent();
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, Pageable.unpaged()))
            .extracting(LabResult::getId).contains(orderedAtB.getId());
        assertThat(labResultRepository.findHandledByHospitals(List.of(hospitalB.getId())))
            .extracting(LabResult::getId).contains(orderedAtB.getId());
        assertThat(labResultRepository.countUnacknowledgedCriticalForOrderingStaff(
            orderedAtB.getLabOrder().getOrderingStaff().getId(), LocalDateTime.now().minusDays(7))).isEqualTo(1);
    }

    @Test
    @DisplayName("QA D820: a result whose order was voided as entered in error loads nowhere, and is kept")
    void voidedOrdersResultsLoadNowhere() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        orderedAtB.setReleased(true);
        orderedAtB.setCriticalNotifiedAt(LocalDateTime.now());
        labResultRepository.saveAndFlush(orderedAtB);
        LabOrder voided = orderedAtB.getLabOrder();
        voided.setStatus(com.example.hms.enums.LabOrderStatus.ENTERED_IN_ERROR);
        voided.setCancellationReason("Wrong patient");
        labOrderRepository.saveAndFlush(voided);
        HospitalContextHolder.clear();
        // Nothing served from the first-level cache: every read below is SQL.
        entityManager.clear();
        UUID sentinel = new UUID(0L, 0L);
        UUID voidedId = orderedAtB.getId();

        assertThat(labResultRepository.findById(voidedId)).isEmpty();
        assertThat(labResultRepository.findByLabOrder_Id(voided.getId())).isEmpty();
        assertThat(labResultRepository.existsByLabOrder_Id(voided.getId())).isFalse();
        // The patient portal's and the chart's reads.
        assertThat(labResultRepository.findPatientResultsReadableAt(patient.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, Pageable.unpaged()))
            .extracting(LabResult::getId)
            .containsExactlyInAnyOrder(performedAtBForA.getId(), otherTestAtB.getId());
        assertThat(labResultRepository.findAllPatientResults(patient.getId(), PageRequest.of(0, 20, NEWEST_FIRST)))
            .extracting(LabResult::getId).doesNotContain(voidedId).hasSize(4);
        // The staff lists, the hospital-wide read and the critical tile.
        assertThat(labResultRepository.findHandledByHospitalForReader(hospitalB.getId(), false, true, sentinel,
            true, sentinel, Pageable.unpaged()).getContent()).extracting(LabResult::getId).doesNotContain(voidedId);
        assertThat(labResultRepository.findAllForReader(false, true, sentinel, false, voided.getId(),
            Pageable.unpaged()).getContent()).isEmpty();
        // The released-only read a non-laboratory reader gets: the voided row was
        // the patient's only released result.
        assertThat(labResultRepository.findAllForReader(true, false, patient.getId(), true, sentinel,
            Pageable.unpaged()).getContent()).isEmpty();
        assertThat(labResultRepository.findHandledByHospitals(List.of(hospitalB.getId())))
            .extracting(LabResult::getId).doesNotContain(voidedId);
        assertThat(labResultRepository.countUnacknowledgedCriticalForOrderingStaff(
            voided.getOrderingStaff().getId(), LocalDateTime.now().minusDays(7))).isZero();
        // The order itself still loads, with its status and reason.
        LabOrder reloaded = labOrderRepository.findById(voided.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(com.example.hms.enums.LabOrderStatus.ENTERED_IN_ERROR);
        assertThat(reloaded.getCancellationReason()).isEqualTo("Wrong patient");
        // And the row is kept: voiding is never a delete.
        Number kept = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM lab.lab_results WHERE id = :id")
            .setParameter("id", voidedId).getSingleResult();
        assertThat(kept.longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("QA D820: a cancelled order's results still load, as before; only a void hides them")
    void cancelledOrdersResultsStillLoad() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        LabOrder cancelled = orderedAtB.getLabOrder();
        cancelled.setStatus(com.example.hms.enums.LabOrderStatus.CANCELLED);
        labOrderRepository.saveAndFlush(cancelled);
        HospitalContextHolder.clear();
        entityManager.clear();

        assertThat(labResultRepository.findById(orderedAtB.getId())).isPresent();
        assertThat(labResultRepository.findByLabOrder_Id(cancelled.getId())).hasSize(1);
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
