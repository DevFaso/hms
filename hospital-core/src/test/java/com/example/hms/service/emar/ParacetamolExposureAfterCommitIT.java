package com.example.hms.service.emar;

import com.example.hms.BaseIT;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.enums.EmploymentType;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NurseTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * CR-03 with the real transaction manager on PostgreSQL. The exposure read must not be able to stop a
 * dose being recorded: a failure in it leaves the dose committed and says the exposure is unknown. Two
 * doses at once on two orders of one patient hold no common lock, and each reads after its commit, so
 * the last to commit counts both.
 */
@DisplayName("CR-03: the exposure is read outside the dose's transaction")
class ParacetamolExposureAfterCommitIT extends BaseIT {

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private NurseTaskService nurseTasks;
    @MockitoSpyBean private ParacetamolExposureCheck exposureCheck;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private OrganizationRepository organizations;
    @Autowired private HospitalRepository hospitals;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private StaffRepository staffRows;
    @Autowired private PatientRepository patients;
    @Autowired private PatientHospitalRegistrationRepository registrations;
    @Autowired private EncounterRepository encounters;
    @Autowired private PrescriptionRepository prescriptions;
    @Autowired private MedicationAdministrationRecordRepository mar;

    private Organization organization;
    private Hospital hospital;
    private User nurseUser;
    private User patientUser;
    private UserRoleHospitalAssignment assignment;
    private Staff staff;
    private Patient patient;
    private PatientHospitalRegistration registration;
    private Encounter encounter;
    private final List<Prescription> orders = new ArrayList<>();

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8) + sequence.incrementAndGet();
    }

    private User user(String tag) {
        String id = unique();
        return users.save(User.builder().username(tag + id).passwordHash("x").email(tag + id + "@test.local")
            .firstName("Exposure").lastName(tag).phoneNumber("+22670" + id).isActive(true).build());
    }

    @BeforeEach
    void setUp() {
        organization = organizations.save(Organization.builder().name("Exposure Network").code("ORG-EXP-" + unique())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitals.save(Hospital.builder().name("Exposure Hospital").code("HEX" + unique())
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226558" + unique()).email("exp" + unique() + "@hospital.test")
            .organization(organization).build());
        Role doctor = roles.findByCode("ROLE_DOCTOR").orElseGet(() -> roles.save(
            Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor role").build()));
        nurseUser = user("nurse");
        patientUser = user("patient");
        assignment = assignments.save(UserRoleHospitalAssignment.builder().user(nurseUser).hospital(hospital)
            .role(doctor).active(true).assignmentCode("A" + unique()).description("Doctor assignment")
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).build());
        staff = staffRows.save(Staff.builder().user(nurseUser).hospital(hospital).assignment(assignment)
            .employmentType(EmploymentType.FULL_TIME).jobTitle(com.example.hms.enums.JobTitle.PHYSICIAN)
            .licenseNumber("LIC-" + unique()).name("Dr. Exposure").active(true).build());
        patient = patients.save(Patient.builder().firstName("Fatimata").lastName("Zongo")
            .dateOfBirth(LocalDate.now().minusYears(35)).gender("F").address("Patient address")
            .city("Ouagadougou").country("Burkina Faso").phoneNumberPrimary("+22678" + unique())
            .email("fz" + unique() + "@patient.test").organizationId(organization.getId())
            .hospitalId(hospital.getId()).user(patientUser).build());
        registration = registrations.save(PatientHospitalRegistration.builder().patient(patient).hospital(hospital)
            .mrn("EXP" + unique()).registrationDate(LocalDate.now()).active(true).build());
        encounter = encounters.save(Encounter.builder().patient(patient).staff(staff).hospital(hospital)
            .assignment(assignment).encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
            .code("ENC-" + unique()).build());
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(exposureCheck);
        for (Prescription order : orders) {
            mar.deleteAll(mar.findByPrescription_IdOrderByScheduledTimeDesc(order.getId()));
            prescriptions.deleteById(order.getId());
        }
        encounters.deleteById(encounter.getId());
        registrations.deleteById(registration.getId());
        patients.deleteById(patient.getId());
        staffRows.deleteById(staff.getId());
        assignments.deleteById(assignment.getId());
        users.deleteById(nurseUser.getId());
        users.deleteById(patientUser.getId());
        hospitals.deleteById(hospital.getId());
        organizations.deleteById(organization.getId());
    }

    private Prescription signedParacetamol() {
        Prescription order = prescriptions.save(Prescription.builder().patient(patient).staff(staff)
            .encounter(encounter).hospital(hospital).assignment(assignment)
            .medicationName("Paracétamol").medicationDisplayName("Paracétamol").route("PO")
            .dosage("500 mg").frequency("si besoin").asNeeded(true).prnMinIntervalHours(4)
            .quantity(BigDecimal.TEN).quantityUnit("cp").status(PrescriptionStatus.SIGNED).build());
        orders.add(order);
        return order;
    }

    private NurseMedicationTaskResponseDTO give(Prescription order) {
        NurseMedicationAdministrationRequestDTO request = new NurseMedicationAdministrationRequestDTO();
        request.setStatus("GIVEN");
        request.setOverrideReason("Scanner en panne");
        return nurseTasks.recordMedicationAdministration(order.getId(), nurseUser.getId(), hospital.getId(), request);
    }

    private List<MedicationAdministrationRecord> doses(Prescription order) {
        return mar.findByPrescription_IdOrderByScheduledTimeDesc(order.getId());
    }

    @Test
    void theRealReadOutsideTheDoseCountsTheDoseJustRecorded() {
        Prescription order = signedParacetamol();

        NurseMedicationTaskResponseDTO answer = give(order);

        assertThat(answer.getCdsAdvisories()).extracting(CdsCard::summary)
            .anySatisfy(summary -> assertThat(summary).contains("500 mg of paracetamol recorded as given"));
    }

    /**
     * The failure a repository call leaves behind: a transaction marked rollback-only. Joined to the
     * dose's transaction it would undo the dose at its commit, as an UnexpectedRollbackException.
     */
    @Test
    void aFailedReadLeavesTheDoseCommittedAndSaysTheExposureIsUnknown() {
        Prescription order = signedParacetamol();
        Mockito.doAnswer(inv -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                status.setRollbackOnly();
                throw new IllegalStateException("the read failed");
            });
            return List.of();
        }).when(exposureCheck).cards(any(), any(), anyBoolean());

        NurseMedicationTaskResponseDTO answer = give(order);

        assertThat(doses(order)).singleElement().satisfies(dose -> {
            assertThat(dose.getStatus()).isEqualTo(MedicationAdministrationStatus.GIVEN);
            assertThat(dose.getAdministeredAt()).isNotNull();
        });
        assertThat(answer.getCdsAdvisories()).extracting(CdsCard::summary)
            .anySatisfy(summary -> assertThat(summary).contains("unknown, not zero"));
    }

    /** Both doses wait at the read until the other has reached it: if a read ran before its commit, neither would see the other. */
    @Test
    void ofTwoDosesAtOnceOnTwoOrdersBothAreCountedWithNoLockBetweenThem() throws Exception {
        Prescription first = signedParacetamol();
        Prescription second = signedParacetamol();
        CyclicBarrier bothAtTheRead = new CyclicBarrier(2);
        Mockito.doAnswer(inv -> {
            bothAtTheRead.await(30, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(exposureCheck).cards(any(), any(), anyBoolean());
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<NurseMedicationTaskResponseDTO> one = threads.submit(() -> give(first));
            Future<NurseMedicationTaskResponseDTO> two = threads.submit(() -> give(second));

            List<String> details = new ArrayList<>();
            for (Future<NurseMedicationTaskResponseDTO> answer : List.of(one, two)) {
                answer.get(60, TimeUnit.SECONDS).getCdsAdvisories().forEach(card -> details.add(card.detail()));
            }

            assertThat(doses(first)).hasSize(1);
            assertThat(doses(second)).hasSize(1);
            assertThat(details).anySatisfy(detail ->
                assertThat(detail).contains("Documented administered exposure: 1000 mg recorded as given"));
        } finally {
            threads.shutdownNow();
        }
    }
}
