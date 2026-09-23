package com.example.hms.service;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox write that runs AFTER the clinical transaction commits.
 *
 * <p>{@code LabResultServiceImpl} defers the HL7 outbound row to
 * {@code TransactionCallbacks.afterCommit}, which fires with no transaction on
 * the thread. A plain {@code @Transactional} (REQUIRED) on the id-based
 * enqueue joined the already-committed one instead of opening its own, and the
 * ORU row was silently never committed — the laboratory's results simply
 * stopped reaching the instrument interface. This drives the real bean the way
 * the callback does, with nothing active, and asserts the row is there
 * afterwards.
 */
class InstrumentOutboxAfterCommitIT extends BaseIT {

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private InstrumentOutboxService instrumentOutboxService;
    @Autowired private InstrumentOutboxRepository outboxRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabResultRepository labResultRepository;

    private LabResult result;

    @BeforeEach
    void seed() {
        wipe();

        Organization organization = organizationRepository.save(Organization.builder()
            .name("Outbox Network").code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        Hospital hospital = hospitalRepository.save(Hospital.builder()
            .name("Outbox Hospital").code("H" + nextId())
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226555" + nextId()).email("outbox" + nextId() + "@hospital.test")
            .organization(organization).build());

        Role role = roleRepository.findByCode("ROLE_LAB_SCIENTIST")
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("LAB_SCIENTIST").code("ROLE_LAB_SCIENTIST").description("Lab scientist").build()));
        User user = userRepository.save(buildUser("scientist"));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId()).description("Lab scientist")
            .user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        Staff staff = staffRepository.save(Staff.builder()
            .user(user).hospital(hospital).assignment(assignment)
            .jobTitle(JobTitle.DOCTOR).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId()).name("Dr. Outbox").active(true).build());

        String suffix = nextId();
        User patientUser = userRepository.save(buildUser("patient"));
        Patient patient = patientRepository.save(Patient.builder()
            .firstName("Awa").lastName("Kone").dateOfBirth(LocalDate.of(1990, 5, 2)).gender("F")
            .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix).email("awa" + suffix + "@patient.test")
            .emergencyContactName("Ali Kone").emergencyContactPhone("+22679" + suffix)
            .organizationId(organization.getId()).hospitalId(hospital.getId())
            .user(patientUser).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("MRN-" + suffix)
            .registrationDate(LocalDate.now()).active(true).build());

        LabTestDefinition definition = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("NA-" + nextId()).name("Sodium").unit("mmol/L").build());

        LabOrder order = labOrderRepository.save(LabOrder.builder()
            .patient(patient).orderingStaff(staff).hospital(hospital).assignment(assignment)
            .labTestDefinition(definition).orderDatetime(LocalDateTime.now().minusHours(1))
            .status(LabOrderStatus.RESULTED).clinicalIndication("Electrolyte monitoring")
            .medicalNecessityNote("Monitoring").primaryDiagnosisCode("E87.6")
            .orderChannel(LabOrderChannel.ELECTRONIC).documentationSharedWithLab(true)
            .build());

        result = labResultRepository.save(LabResult.builder()
            .labOrder(order).assignment(assignment)
            .resultValue("140").resultUnit("mmol/L").resultDate(LocalDateTime.now())
            .build());
    }

    @AfterEach
    void clear() {
        wipe();
    }

    @Test
    @DisplayName("the id-based enqueue commits its row when called with no transaction, as afterCommit does")
    void enqueueOutsideATransactionPersistsTheRow() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
            .as("this test must call the service the way the after-commit callback does: with nothing active")
            .isFalse();
        assertThat(outboxRepository.count()).isZero();

        instrumentOutboxService.enqueueResultObservation(result.getId());

        assertThat(outboxRepository.findAll())
            .as("the ORU row must be committed by the enqueue's own transaction")
            .hasSize(1)
            .allSatisfy(row -> assertThat(row.getMessageType()).isEqualTo("ORU^R01"));
    }

    @Test
    @DisplayName("an unknown or absent result id is a no-op, not a failure")
    void unknownIdIsIgnored() {
        instrumentOutboxService.enqueueResultObservation(UUID.randomUUID());
        // a null id means the callback fired for something that was not saved
        instrumentOutboxService.enqueueResultObservation((UUID) null);

        assertThat(outboxRepository.count()).isZero();
    }

    private void wipe() {
        outboxRepository.deleteAll();
        labResultRepository.deleteAll();
        labOrderRepository.deleteAll();
        labTestDefinitionRepository.deleteAll();
        registrationRepository.deleteAll();
        patientRepository.deleteAll();
        staffRepository.deleteAll();
        assignmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        hospitalRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix).passwordHash("hashed-password")
            .email(prefix + suffix + "@example.test")
            .firstName(prefix + "FN").lastName("User" + suffix)
            .phoneNumber("+22677" + suffix).isActive(true).build();
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
