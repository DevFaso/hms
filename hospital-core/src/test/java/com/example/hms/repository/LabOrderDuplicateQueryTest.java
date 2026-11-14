package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.lab.LabOrderDuplicateCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LabOrderRepository.findActiveSameTestSince} against a real database:
 * the query behind the lab-order duplicate warning. It matches an active order
 * of the same test (by definition id, or by test code) for the same patient at
 * the same hospital within the window, and nothing else.
 */
@TenantScopedDataJpaTest
class LabOrderDuplicateQueryTest {

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
    private Role role;
    private Patient patient;
    private LabTestDefinition glucose;
    private LabTestDefinition hemoglobin;
    private Staff atA;
    private Staff atB;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospitalA = hospitalRepository.save(Hospital.builder().name("Hopital A").code("DUA" + nextId()).build());
        Hospital hospitalB = hospitalRepository.save(Hospital.builder().name("Hopital B").code("DUB" + nextId()).build());
        role = roleRepository.save(Role.builder()
            .name("Doctor " + nextId()).code("ROLE_DUP_DOCTOR_" + nextId()).description("ordering").build());
        patient = patient("Awa");
        glucose = definition("GLU", "Fasting glucose");
        hemoglobin = definition("HGB", "Hemoglobin");
        atA = staffAt(hospitalA);
        atB = staffAt(hospitalB);
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("an active order of the same test, same patient, same hospital, in the window is a match")
    void activeSameTestMatches() {
        LabOrder first = order(patient, glucose, atA, LabOrderStatus.ORDERED, 1);
        LabOrder collected = order(patient, glucose, atA, LabOrderStatus.COLLECTED, 2);

        assertThat(find(glucose, null)).extracting(LabOrder::getId)
            .containsExactly(first.getId(), collected.getId());
    }

    @Test
    @DisplayName("a cancelled, completed, resulted or voided earlier order is not a match")
    void closedOrdersDoNotMatch() {
        for (LabOrderStatus closed : List.of(LabOrderStatus.CANCELLED, LabOrderStatus.COMPLETED,
                LabOrderStatus.RESULTED, LabOrderStatus.VERIFIED, LabOrderStatus.ENTERED_IN_ERROR)) {
            order(patient, glucose, atA, closed, 1);
        }
        assertThat(find(glucose, null)).isEmpty();
    }

    @Test
    @DisplayName("a different test is not a match")
    void differentTestDoesNotMatch() {
        order(patient, hemoglobin, atA, LabOrderStatus.ORDERED, 1);
        assertThat(find(glucose, null)).isEmpty();
    }

    @Test
    @DisplayName("tenancy: another hospital's active order of the same test is not a match")
    void anotherHospitalsOrderDoesNotMatch() {
        order(patient, glucose, atB, LabOrderStatus.ORDERED, 1);
        assertThat(find(glucose, null)).isEmpty();
    }

    @Test
    @DisplayName("another patient's order and an order older than the window are not matches")
    void otherPatientAndOldOrdersDoNotMatch() {
        order(patient("Issa"), glucose, atA, LabOrderStatus.ORDERED, 1);
        order(patient, glucose, atA, LabOrderStatus.ORDERED,
            (int) LabOrderDuplicateCheck.WINDOW.toHours() + 2);
        assertThat(find(glucose, null)).isEmpty();
    }

    @Test
    @DisplayName("the same test code under another definition (a new version) is a match; an order never matches itself")
    void sameCodeMatchesAndSelfIsExcluded() {
        LabOrder earlier = order(patient, glucose, atA, LabOrderStatus.ORDERED, 1);
        LabTestDefinition otherVersion = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode(glucose.getTestCode()).name("Fasting glucose v2").unit("mmol/L").build());

        assertThat(labOrderRepository.findActiveSameTestSince(patient.getId(), hospitalA.getId(),
            otherVersion.getId(), otherVersion.getTestCode(), LabOrderDuplicateCheck.ACTIVE,
            LocalDateTime.now().minus(LabOrderDuplicateCheck.WINDOW), UUID.randomUUID()))
            .extracting(LabOrder::getId).containsExactly(earlier.getId());
        assertThat(find(glucose, earlier.getId())).isEmpty();
    }

    @Test
    @DisplayName("a stored code that differs only in case or spaces still matches (both sides normalised)")
    void codesAreComparedNormalisedOnBothSides() {
        // Rex on #1150: the caller's code was trimmed and upper-cased, the
        // stored one was not, so a legacy row " glu-x " missed "GLU-X".
        LabOrder earlier = order(patient, glucose, atA, LabOrderStatus.ORDERED, 1);
        String code = glucose.getTestCode();
        entityManager.createQuery("UPDATE LabTestDefinition d SET d.testCode = :code WHERE d.id = :id")
            .setParameter("code", "  " + code.toLowerCase(java.util.Locale.ROOT) + " ")
            .setParameter("id", glucose.getId())
            .executeUpdate();
        entityManager.clear();
        LabTestDefinition otherVersion = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode(code).name("Fasting glucose v3").unit("mmol/L").build());

        assertThat(labOrderRepository.findActiveSameTestSince(patient.getId(), hospitalA.getId(),
            otherVersion.getId(), code, LabOrderDuplicateCheck.ACTIVE,
            LocalDateTime.now().minus(LabOrderDuplicateCheck.WINDOW), UUID.randomUUID()))
            .extracting(LabOrder::getId).containsExactly(earlier.getId());
    }

    private List<LabOrder> find(LabTestDefinition test, UUID exclude) {
        return labOrderRepository.findActiveSameTestSince(patient.getId(), hospitalA.getId(), test.getId(),
            test.getTestCode(), LabOrderDuplicateCheck.ACTIVE,
            LocalDateTime.now().minus(LabOrderDuplicateCheck.WINDOW),
            exclude != null ? exclude : UUID.randomUUID());
    }

    private LabTestDefinition definition(String prefix, String name) {
        return labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode(prefix + nextId()).name(name).unit("mmol/L").build());
    }

    private LabOrder order(Patient who, LabTestDefinition test, Staff orderingStaff, LabOrderStatus status, int hoursAgo) {
        return labOrderRepository.save(LabOrder.builder()
            .patient(who)
            .orderingStaff(orderingStaff)
            .labTestDefinition(test)
            .hospital(orderingStaff.getHospital())
            .assignment(orderingStaff.getAssignment())
            .status(status)
            .orderDatetime(LocalDateTime.now().minusHours(hoursAgo))
            .clinicalIndication("Duplicate fixture")
            .build());
    }

    private Patient patient(String firstName) {
        String suffix = nextId();
        User user = userRepository.save(user("pt"));
        return patientRepository.save(Patient.builder()
            .firstName(firstName)
            .lastName("Dup" + suffix)
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

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@dup-labs.test")
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
