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
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LabResultRepository.findTrendReadableAt} against a real database.
 *
 * <p>The service tests stub this query, and the service's own filter can only
 * REMOVE rows — so a query that silently dropped, say, the rows a laboratory
 * performed for another hospital (an inner join where the performing hospital
 * is null on most orders, a mistyped path) would pass every one of them while
 * the performing laboratory's trend lost its data in production. This pins
 * the three kinds of readable row, and the ones that must not come back.
 */
@DataJpaTest
@ActiveProfiles("test")
// Exactly PatientRepositoryTenantScopeTest's configuration, so the two share
// one cached context. A test-specific JpaConfig made this a new context, and
// one more EntityManagerFactory in the test JVM was enough to exhaust the heap
// under the full suite (PatientRepositoryRegistrationScopeTest's context then
// failed to load with OutOfMemoryError).
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class LabResultTrendReadableQueryTest {

    private static final PageRequest WINDOW = PageRequest.of(0, 12);
    private static final UUID NO_HOSPITAL = new UUID(0L, 0L);

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

    private LabResult orderedAtB;
    private LabResult performedAtBForA;
    private LabResult orderedAtT;
    private LabResult orderedAndRunAtA;
    private LabResult otherTestAtB;
    private LabResult otherPatientAtB;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospitalA = hospitalRepository.save(Hospital.builder().name("Hopital A").code("TRA" + nextId()).build());
        hospitalB = hospitalRepository.save(Hospital.builder().name("Hopital B").code("TRB" + nextId()).build());
        hospitalT = hospitalRepository.save(Hospital.builder().name("Hopital T").code("TRT" + nextId()).build());
        role = roleRepository.save(Role.builder()
            .name("Doctor " + nextId()).code("ROLE_TREND_DOCTOR_" + nextId()).description("ordering").build());

        patient = patient("Awa");
        otherPatient = patient("Issa");
        hemoglobin = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("HGB" + nextId()).name("Hemoglobin").unit("g/dL").build());
        glucose = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("GLU" + nextId()).name("Glucose").unit("mmol/L").build());

        Staff atA = staffAt(hospitalA);
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
    @DisplayName("B reads what it ordered and what its laboratory performed, and nothing of A's own")
    void orderedHereAndPerformedHere() {
        List<LabResult> rows = labResultRepository.findTrendReadableAt(patient.getId(), hemoglobin.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, WINDOW);

        assertThat(rows).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId(), performedAtBForA.getId());
    }

    @Test
    @DisplayName("a treatment relationship to T adds T's rows, newest first")
    void treatmentRelationship() {
        List<LabResult> rows = labResultRepository.findTrendReadableAt(patient.getId(), hemoglobin.getId(),
            Set.of(hospitalB.getId(), hospitalT.getId()), hospitalB.getId(), false, WINDOW);

        assertThat(rows).extracting(LabResult::getId)
            .containsExactly(orderedAtB.getId(), performedAtBForA.getId(), orderedAtT.getId());
    }

    @Test
    @DisplayName("the performing hospital is not a way into A's own orders")
    void aHospitalSeesNothingItNeitherOrderedNorRan() {
        UUID elsewhere = hospitalRepository.save(Hospital.builder().name("Hopital C").code("TRC" + nextId()).build())
            .getId();

        assertThat(labResultRepository.findTrendReadableAt(patient.getId(), hemoglobin.getId(),
            Set.of(elsewhere), elsewhere, false, WINDOW)).isEmpty();
    }

    @Test
    @DisplayName("global view reads every hospital's rows, but still only this patient's and this test's")
    void globalView() {
        List<LabResult> rows = labResultRepository.findTrendReadableAt(patient.getId(), hemoglobin.getId(),
            Set.of(NO_HOSPITAL), null, true, WINDOW);

        assertThat(rows).extracting(LabResult::getId).containsExactly(
            orderedAtB.getId(), performedAtBForA.getId(), orderedAtT.getId(), orderedAndRunAtA.getId());
        assertThat(rows).extracting(LabResult::getId)
            .doesNotContain(otherTestAtB.getId(), otherPatientAtB.getId());
    }

    @Test
    @DisplayName("an unknown patient reads as nothing, like a patient with nothing readable")
    void unknownPatient() {
        assertThat(labResultRepository.findTrendReadableAt(UUID.randomUUID(), hemoglobin.getId(),
            Set.of(hospitalB.getId()), hospitalB.getId(), false, WINDOW)).isEmpty();
    }

    private Patient patient(String firstName) {
        String suffix = nextId();
        User user = userRepository.save(user("pt"));
        return patientRepository.save(Patient.builder()
            .firstName(firstName)
            .lastName("Trend" + suffix)
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
            .clinicalIndication("Trend fixture")
            .build());
    }

    /** A SYSTEM row, {@code daysAgo} old, so the ORDER BY is deterministic. */
    private LabResult result(LabOrder order, int daysAgo) {
        return labResultRepository.save(LabResult.builder()
            .labOrder(order)
            .actorType(ActorType.SYSTEM)
            .actorLabel("MLLP:TREND/FIXTURE")
            .resultValue(String.valueOf(10 + daysAgo))
            .resultDate(LocalDateTime.now().minusDays(daysAgo))
            .build());
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@trend.test")
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
