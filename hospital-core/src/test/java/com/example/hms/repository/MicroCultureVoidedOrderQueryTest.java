package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.MicroCultureResult;
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
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D820, self-review of #960: a culture report on an order voided as entered
 * in error loads nowhere, against a real database. The service tests mock the
 * repository, so only this sees the entity restriction: the patient's chart
 * list, the hospital's microbiology worklist, the FHIR DiagnosticReport search
 * and read, and the findById every edit, isolate, susceptibility and finalize
 * goes through. A cancelled order's culture still loads; the voided row is kept.
 */
@TenantScopedDataJpaTest
class MicroCultureVoidedOrderQueryTest {

    @Autowired private MicroCultureResultRepository cultureRepository;
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

    private Hospital hospital;
    private Patient patient;
    private MicroCultureResult voidedCulture;
    private MicroCultureResult keptCulture;
    private LabOrder voidedOrder;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hopital M").code("MCV" + nextId()).build());
        Role role = roleRepository.save(Role.builder()
            .name("Lab " + nextId()).code("ROLE_MICRO_VOID_" + nextId()).description("micro").build());
        patient = patient();
        LabTestDefinition bloodCulture = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("BCX" + nextId()).name("Blood culture").build());
        Staff staff = staffAt(role);

        voidedOrder = order(bloodCulture, staff);
        LabOrder keptOrder = order(bloodCulture, staff);
        // The QA scenario: a PRELIMINARY growth culture started on the order.
        voidedCulture = culture(voidedOrder);
        keptCulture = culture(keptOrder);
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a voided order's PRELIMINARY culture leaves the chart, the worklist and FHIR, and is kept")
    void voidedOrdersCultureLoadsNowhere() {
        markOrder(voidedOrder, LabOrderStatus.ENTERED_IN_ERROR);
        UUID voidedId = voidedCulture.getId();

        // The patient's chart (MicroCultureService.getForPatient).
        assertThat(cultureRepository.findForPatient(patient.getId(), hospital.getId()))
            .extracting(MicroCultureResult::getId).containsExactly(keptCulture.getId());
        // The microbiology worklist, unfiltered and on the PRELIMINARY tab.
        assertThat(cultureRepository.findForHospital(hospital.getId(), null, Pageable.unpaged()).getContent())
            .extracting(MicroCultureResult::getId).containsExactly(keptCulture.getId());
        assertThat(cultureRepository.findForHospital(hospital.getId(), MicroCultureStatus.PRELIMINARY,
            Pageable.unpaged()).getContent())
            .extracting(MicroCultureResult::getId).containsExactly(keptCulture.getId());
        // The FHIR DiagnosticReport search.
        assertThat(cultureRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
            patient.getId(), hospital.getId(), Pageable.unpaged()).getContent())
            .extracting(MicroCultureResult::getId).containsExactly(keptCulture.getId());
        assertThat(cultureRepository.findByLabOrder_IdOrderByCreatedAtDesc(voidedOrder.getId())).isEmpty();
        // findById: the FHIR read, getCulture, and every change, finalize included.
        assertThat(cultureRepository.findById(voidedId)).isEmpty();

        // The order itself still loads with its status, and the culture row is kept.
        assertThat(labOrderRepository.findById(voidedOrder.getId()).orElseThrow().getStatus())
            .isEqualTo(LabOrderStatus.ENTERED_IN_ERROR);
        Number kept = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM lab.micro_culture_results WHERE id = :id")
            .setParameter("id", voidedId).getSingleResult();
        assertThat(kept.longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a cancelled order's culture still loads, as before; only a void hides it")
    void cancelledOrdersCultureStillLoads() {
        markOrder(voidedOrder, LabOrderStatus.CANCELLED);

        assertThat(cultureRepository.findById(voidedCulture.getId())).isPresent();
        assertThat(cultureRepository.findForPatient(patient.getId(), hospital.getId()))
            .extracting(MicroCultureResult::getId)
            .containsExactlyInAnyOrder(voidedCulture.getId(), keptCulture.getId());
    }

    private void markOrder(LabOrder order, LabOrderStatus status) {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        order.setStatus(status);
        order.setCancellationReason("Wrong patient");
        labOrderRepository.saveAndFlush(order);
        HospitalContextHolder.clear();
        // Nothing served from the first-level cache: every read below is SQL.
        entityManager.clear();
    }

    private MicroCultureResult culture(LabOrder order) {
        return cultureRepository.saveAndFlush(MicroCultureResult.builder()
            .labOrder(order)
            .patient(patient)
            .hospital(hospital)
            .specimenSource("Blood")
            .growthResult(MicroGrowthResult.GROWTH)
            .status(MicroCultureStatus.PRELIMINARY)
            .build());
    }

    private Patient patient() {
        String suffix = nextId();
        User user = userRepository.save(user("pt"));
        return patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Micro" + suffix)
            .dateOfBirth(LocalDate.of(1990, 3, 15))
            .phoneNumberPrimary("+22671" + suffix)
            .user(user)
            .hospitalId(hospital.getId())
            .build());
    }

    private Staff staffAt(Role role) {
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

    private LabOrder order(LabTestDefinition test, Staff orderingStaff) {
        return labOrderRepository.save(LabOrder.builder()
            .patient(patient)
            .orderingStaff(orderingStaff)
            .labTestDefinition(test)
            .hospital(hospital)
            .assignment(orderingStaff.getAssignment())
            .orderDatetime(LocalDateTime.now().minusDays(1))
            .clinicalIndication("Micro fixture")
            .build());
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@micro-void.test")
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
