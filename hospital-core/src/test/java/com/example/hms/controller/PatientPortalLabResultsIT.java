package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ActorType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemHistoryRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3 through the real chain: {@code GET /me/patient/lab-results} as the
 * patient, against rows persisted in the test database. An analyzer result
 * that the lab has not released yet must reach the patient as a pending
 * test and nothing more; once released, the value follows. The staff read
 * of the same rows ({@code GET /patients/{id}/lab-results}) keeps the
 * preliminary value and says {@code released=false} so a UI can label it.
 */
@AutoConfigureMockMvc
class PatientPortalLabResultsIT extends BaseIT {

    private static final String API = "/api";
    private static final String MY_LAB_RESULTS = "/api/me/patient/lab-results";
    private static final String STAFF_LAB_RESULTS = "/api/patients/{id}/lab-results";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String PRELIMINARY_VALUE = "13.7";
    private static final String PRELIMINARY_NOTES = "preliminary - repeat requested";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private PatientProblemRepository patientProblemRepository;
    @Autowired private PatientProblemHistoryRepository patientProblemHistoryRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabResultRepository labResultRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Hospital hospital;
    private User patientUser;
    private User doctorUser;
    private Patient patient;
    private LabTestDefinition hemoglobin;
    private LabResult result;

    @BeforeEach
    void seedAnUnreleasedAnalyzerResult() {
        auditEventLogRepository.deleteAllInBatch();
        labResultRepository.deleteAll();
        labOrderRepository.deleteAll();
        labTestDefinitionRepository.deleteAll();
        registrationRepository.deleteAll();
        // patient_problems (and their history) reference staff: a sibling IT
        // that records a diagnosis leaves rows here, and deleting staff under
        // them fails on fk_problem_staff. Children first.
        patientProblemHistoryRepository.deleteAllInBatch();
        patientProblemRepository.deleteAllInBatch();
        patientRepository.deleteAll();
        staffRepository.deleteAll();
        assignmentRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        hospitalRepository.deleteAll();
        organizationRepository.deleteAll();

        Organization organization = organizationRepository.save(Organization.builder()
            .name("Test Health Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Test Teaching Hospital")
            .code("HTH" + nextId())
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226555" + nextId())
            .email("contact" + nextId() + "@hospital.test")
            .organization(organization)
            .build());

        Role doctorRole = roleRepository.save(Role.builder()
            .name("Doctor").code(ROLE_DOCTOR).description("Physician role").build());
        doctorUser = userRepository.save(buildUser("doctor"));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description("Ordering doctor")
            .user(doctorUser)
            .hospital(hospital)
            .role(doctorRole)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        Staff doctor = staffRepository.save(Staff.builder()
            .user(doctorUser)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(JobTitle.SURGEON)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr. " + doctorUser.getFirstName())
            .active(true)
            .build());

        patientUser = userRepository.save(buildUser("patient"));
        String suffix = nextId();
        patient = patientRepository.save(Patient.builder()
            .firstName("Aminata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("aminata" + suffix + "@patient.test")
            .emergencyContactName("Issa Diallo")
            .emergencyContactPhone("+22679" + suffix)
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .user(patientUser)
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN-" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build());

        hemoglobin = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("HGB")
            .name("Hemoglobin")
            .category("HEMATOLOGY")
            .unit("g/dL")
            .hospital(hospital)
            .assignment(assignment)
            .build());
        LabOrder order = labOrderRepository.save(LabOrder.builder()
            .patient(patient)
            .orderingStaff(doctor)
            .labTestDefinition(hemoglobin)
            .hospital(hospital)
            .assignment(assignment)
            .orderDatetime(LocalDateTime.now().minusHours(3))
            .clinicalIndication("Anaemia work-up")
            .status(LabOrderStatus.RESULTED)
            .build());
        // What the MLLP ingest writes: a SYSTEM row, unreleased, with a value.
        result = labResultRepository.save(LabResult.builder()
            .labOrder(order)
            .actorType(ActorType.SYSTEM)
            .actorLabel("MLLP:SYSMEX/LAB_A")
            .resultValue(PRELIMINARY_VALUE)
            .resultUnit("g/dL")
            .resultDate(LocalDateTime.now().minusHours(1))
            .abnormalFlag(AbnormalFlag.ABNORMAL_HIGH)
            .notes(PRELIMINARY_NOTES)
            .referenceRange("12.0-15.5")
            .build());
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        auditEventLogRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("B3 - the patient sees a pending test and nothing of the unreleased value")
    void unreleasedResultReachesThePatientWithoutItsValue() throws Exception {
        mockMvc.perform(get(MY_LAB_RESULTS).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(result.getId().toString()))
            .andExpect(jsonPath("$.data[0].testName").value("Hemoglobin"))
            .andExpect(jsonPath("$.data[0].testCode").value("HGB"))
            .andExpect(jsonPath("$.data[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data[0].released").value(false))
            .andExpect(jsonPath("$.data[0].value").doesNotExist())
            .andExpect(jsonPath("$.data[0].unit").doesNotExist())
            .andExpect(jsonPath("$.data[0].referenceRange").doesNotExist())
            .andExpect(jsonPath("$.data[0].notes").doesNotExist())
            .andExpect(jsonPath("$.data[0].performedBy").doesNotExist());
    }

    @Test
    @DisplayName("B3 - once the lab releases it, the same row reaches the patient with its value and direction")
    void releasedResultReachesThePatientWhole() throws Exception {
        result.setReleased(true);
        result.setReleasedAt(LocalDateTime.now());
        result.setReleasedByDisplay("Lab supervisor");
        labResultRepository.save(result);

        mockMvc.perform(get(MY_LAB_RESULTS).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].released").value(true))
            .andExpect(jsonPath("$.data[0].value").value(PRELIMINARY_VALUE))
            .andExpect(jsonPath("$.data[0].unit").value("g/dL"))
            .andExpect(jsonPath("$.data[0].notes").value(PRELIMINARY_NOTES))
            // B18 - no configured range to grade against, so the analyzer's H decides.
            .andExpect(jsonPath("$.data[0].status").value("ABNORMAL_HIGH"));
    }

    /** The hospital's own range for hemoglobin: 13.7 sits inside it. */
    private void givenAConfiguredRange() {
        hemoglobin.setReferenceRanges(List.of(LabTestReferenceRange.builder()
            .minValue(12.0).maxValue(15.5).unit("g/dL").build()));
        labTestDefinitionRepository.save(hemoglobin);
    }

    private void release(AbnormalFlag flag) {
        result.setAbnormalFlag(flag);
        result.setReleased(true);
        result.setReleasedAt(LocalDateTime.now());
        result.setReleasedByDisplay("Lab supervisor");
        labResultRepository.save(result);
    }

    @Test
    @DisplayName("B18 - a value inside the configured range that the analyzer still flagged H reads ABNORMAL_HIGH")
    void rangeSaysNormalButAnalyzerFlaggedHigh() throws Exception {
        givenAConfiguredRange();
        release(AbnormalFlag.ABNORMAL_HIGH);

        mockMvc.perform(get(MY_LAB_RESULTS).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].referenceRange").value("12 - 15.5 g/dL"))
            .andExpect(jsonPath("$.data[0].status").value("ABNORMAL_HIGH"));
    }

    @Test
    @DisplayName("B18 - an HH row with a configured range reads CRITICAL, never merely ABNORMAL_HIGH")
    void analyzerCriticalOutranksTheRange() throws Exception {
        givenAConfiguredRange();
        release(AbnormalFlag.CRITICAL);

        mockMvc.perform(get(MY_LAB_RESULTS).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("CRITICAL"));
    }

    @Test
    @DisplayName("staff read keeps the preliminary value and labels it released=false")
    void staffReadKeepsThePreliminaryValue() throws Exception {
        mockMvc.perform(get(STAFF_LAB_RESULTS, patient.getId()).contextPath(API)
                .param("hospitalId", hospital.getId().toString())
                .with(doctor()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].status").value("PENDING"))
            .andExpect(jsonPath("$[0].released").value(false))
            .andExpect(jsonPath("$[0].value").value(PRELIMINARY_VALUE));
    }

    private RequestPostProcessor patient() {
        return signedInAs(patientUser, ROLE_PATIENT);
    }

    private RequestPostProcessor doctor() {
        return signedInAs(doctorUser, ROLE_DOCTOR);
    }

    /** A signed-in user as the JWT filter builds one: CustomUserDetails carrying the user id and the role. */
    private static RequestPostProcessor signedInAs(User user, String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        CustomUserDetails details = new CustomUserDetails(user.getId(), user.getUsername(), "n/a", true, authorities);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, authorities));
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@example.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build();
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
