package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Notification;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.CriticalValueReadBackRequestDTO;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hospital → laboratory → ordering doctor, through the REAL filter chain and
 * the real services on H2.
 *
 * <p>The audit that led here found the pieces individually correct and the
 * flow broken: nothing advanced {@code LabOrderStatus}, so a released result
 * never reached the doctor's review queue (B2); the edge matcher 403'd roles
 * the annotation admits (B8); release admitted the ordering roles the
 * authority class excludes (B10) and skipped the tenancy guard (B11); and
 * normal results were auto-released with no human (B5). Each step below is
 * one of those, asserted at the HTTP boundary.
 */
@AutoConfigureMockMvc
class LabOrderEndToEndIT extends BaseIT {

    private static final String API = "/api";
    private static final String LAB_ORDERS = API + "/lab-orders";
    private static final String LAB_RESULTS = API + "/lab-results";
    private static final String LAB_ORDER_SPECIMENS = LAB_ORDERS + "/{id}/specimens";
    private static final String SPECIMEN_RECEIVE = API + "/lab-specimens/{id}/receive";
    private static final String RESULT_RELEASE = LAB_RESULTS + "/{id}/release";
    private static final String RESULT_ACKNOWLEDGE = LAB_RESULTS + "/{id}/acknowledge";
    private static final String RESULT_READ_BACK = LAB_RESULTS + "/{id}/critical-read-back";
    private static final String REVIEW_QUEUE = API + "/me/results/review-queue";

    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_LAB_TECHNICIAN = "ROLE_LAB_TECHNICIAN";
    private static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";
    private static final String ROLE_LAB_DIRECTOR = "ROLE_LAB_DIRECTOR";
    private static final String ROLE_QUALITY_MANAGER = "ROLE_QUALITY_MANAGER";

    /** Reference range 0–10: a value above it computes to HIGH, which the notifier treats as critical. */
    private static final double RANGE_MAX = 10.0;
    private static final String NORMAL_VALUE = "5";
    private static final String CRITICAL_VALUE = "50";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
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
    @Autowired private LabSpecimenRepository labSpecimenRepository;
    @Autowired private LabResultRepository labResultRepository;
    @Autowired private InstrumentOutboxRepository instrumentOutboxRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Organization organization;
    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private LabTestDefinition testDefinition;

    private Actor doctor;
    private Actor nurse;
    private Actor technician;
    private Actor scientist;
    private Actor director;
    private Actor qualityManager;
    private Actor foreignScientist;

    /** A signed-in person: the user row, their single assignment, and the authority the JWT would carry. */
    private record Actor(User user, UserRoleHospitalAssignment assignment, String role) {
        UUID assignmentId() {
            return assignment.getId();
        }
    }

    @BeforeEach
    void seed() {
        wipe();

        organization = organizationRepository.save(Organization.builder()
            .name("Keneya Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = saveHospital("Ordering Hospital");
        otherHospital = saveHospital("Somewhere Else");

        doctor = actor("doctor", ROLE_DOCTOR, hospital);
        nurse = actor("nurse", ROLE_NURSE, hospital);
        technician = actor("tech", ROLE_LAB_TECHNICIAN, hospital);
        scientist = actor("scientist", ROLE_LAB_SCIENTIST, hospital);
        director = actor("director", ROLE_LAB_DIRECTOR, hospital);
        qualityManager = actor("qm", ROLE_QUALITY_MANAGER, hospital);
        foreignScientist = actor("foreign", ROLE_LAB_SCIENTIST, otherHospital);

        staffRepository.save(Staff.builder()
            .user(doctor.user())
            .hospital(hospital)
            .assignment(doctor.assignment())
            .jobTitle(JobTitle.DOCTOR)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr. " + doctor.user().getFirstName())
            .active(true)
            .build());

        patient = createPatient();

        testDefinition = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("K-" + nextId())
            .name("Potassium")
            .unit("mmol/L")
            .referenceRanges(List.of(LabTestReferenceRange.builder()
                .minValue(0.0).maxValue(RANGE_MAX).unit("mmol/L").build()))
            .build());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // The H2 database is shared with the sibling ITs, whose own cleanup
        // does not know about lab orders: an order left here would pin the
        // doctor's staff row, which pins the assignment, and their
        // deleteAll() fails on the FK. Leave nothing behind.
        wipe();
    }

    // ── the flow ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("order → specimen → result → release → review queue, with every gate on the way")
    void orderTravelsFromTheDoctorToTheLabAndBack() throws Exception {
        // 1. The doctor places the order.
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(3));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);

        // 2. The technician collects and receives the specimen (B2: each
        //    event is the order's state, no transition call needed).
        String specimenId = jsonData(mockMvc.perform(post(LAB_ORDER_SPECIMENS, orderId)
                .contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(LabSpecimenRequestDTO.builder().specimenType("BLOOD").build()))
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn(), "id");
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.COLLECTED);

        mockMvc.perform(post(SPECIMEN_RECEIVE, specimenId).contextPath(API)
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RECEIVED);

        // 3. The scientist posts a result: the order is RESULTED, and the
        //    normal-range value is NOT released behind anyone's back (B5).
        UUID resultId = postResult(scientist, orderId, NORMAL_VALUE).andExpect(status().isCreated())
            .andExpect(jsonPath("$.released").value(false))
            .andReturn().getResponse().getContentAsString().transform(this::idOf);
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RESULTED);
        assertThat(labResultRepository.findById(resultId).orElseThrow().isReleased()).isFalse();

        // 3b. B8: the roles the annotation admits get past the edge too. They
        //     post on a second order so the queue assertion below stays exact.
        UUID secondOrderId = placeOrder(LocalDateTime.now().minusHours(2));
        postResult(director, secondOrderId, NORMAL_VALUE).andExpect(status().isCreated());
        postResult(qualityManager, secondOrderId, NORMAL_VALUE).andExpect(status().isCreated());
        assertThat(orderStatus(secondOrderId)).isEqualTo(LabOrderStatus.RESULTED);

        // 4. B10: the ordering roles cannot release — the annotation says so
        //    before the service is reached.
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(nurse)).with(csrf()))
            .andExpect(status().isForbidden());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isReleased()).isFalse();

        // 5. The scientist releases; the only result of the order is now
        //    released, so the order is COMPLETED (B2).
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(scientist)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.released").value(true));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.COMPLETED);
        // the second order still has two unreleased results
        assertThat(orderStatus(secondOrderId)).isEqualTo(LabOrderStatus.RESULTED);

        // 6. The ordering doctor's review queue now shows exactly that result.
        mockMvc.perform(get(REVIEW_QUEUE).contextPath(API).with(signedInAs(doctor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(resultId.toString()))
            .andExpect(jsonPath("$.data[0].patientId").value(patient.getId().toString()))
            .andExpect(jsonPath("$.data[0].resultValue").value(NORMAL_VALUE));
    }

    @Test
    @DisplayName("B11 — a lab scientist of another hospital neither sees the order nor can attach a result to it")
    void foreignLaboratoryStaffSeeNothing() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(1));

        mockMvc.perform(get(LAB_ORDERS + "/{id}", orderId).contextPath(API).with(signedInAs(foreignScientist)))
            .andExpect(status().isNotFound());

        postResult(foreignScientist, orderId, NORMAL_VALUE).andExpect(status().isNotFound());

        assertThat(labResultRepository.findAll()).isEmpty();
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);
    }

    @Test
    @DisplayName("a critical value notifies the doctor, stays unreleased, and is acknowledged only by a matching read-back")
    void criticalValueClosesTheLoopThroughReadBack() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusMinutes(30));

        UUID resultId = postResult(scientist, orderId, CRITICAL_VALUE).andExpect(status().isCreated())
            .andExpect(jsonPath("$.severityFlag").value("HIGH"))
            .andExpect(jsonPath("$.released").value(false))
            .andReturn().getResponse().getContentAsString().transform(this::idOf);

        LabResult stored = labResultRepository.findById(resultId).orElseThrow();
        assertThat(stored.isReleased()).isFalse();
        assertThat(stored.getCriticalNotifiedAt()).isNotNull();
        List<Notification> forDoctor = notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(doctor.user().getUsername(), false);
        assertThat(forDoctor).hasSize(1);
        assertThat(forDoctor.get(0).getType()).isEqualTo("CRITICAL_LAB_RESULT");

        // Dismissing the alert is not acknowledging a critical value.
        mockMvc.perform(post(RESULT_ACKNOWLEDGE, resultId).contextPath(API).with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isAcknowledged()).isFalse();

        // A wrong read-back is rejected (and recorded), a right one resolves it.
        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack("51")))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isAcknowledged()).isFalse();

        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack(CRITICAL_VALUE)))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));
        LabResult acknowledged = labResultRepository.findById(resultId).orElseThrow();
        assertThat(acknowledged.isAcknowledged()).isTrue();
        assertThat(acknowledged.getCriticalReadBackAt()).isNotNull();
        assertThat(acknowledged.getAcknowledgedByUserId()).isEqualTo(doctor.user().getId());
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private UUID placeOrder(LocalDateTime orderedAt) throws Exception {
        LabOrderRequestDTO request = LabOrderRequestDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospital.getId())
            .testName(testDefinition.getName())
            .status(LabOrderStatus.ORDERED.name())
            .clinicalIndication("Muscle weakness, on diuretics")
            .medicalNecessityNote("Electrolyte monitoring")
            .primaryDiagnosisCode("E87.6")
            .orderChannel("ELECTRONIC")
            .documentationSharedWithLab(true)
            .providerSignature("signed-by-" + doctor.user().getUsername())
            .orderDatetime(orderedAt)
            .orderingStaffId(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctor.user().getId()).orElseThrow().getId())
            .labTestDefinitionId(testDefinition.getId())
            .assignmentId(doctor.assignmentId())
            .build();
        MvcResult created = mockMvc.perform(post(LAB_ORDERS).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("ORDERED"))
            .andReturn();
        return UUID.fromString(jsonData(created, "id"));
    }

    private org.springframework.test.web.servlet.ResultActions postResult(Actor actor, UUID orderId, String value) throws Exception {
        LabResultRequestDTO request = LabResultRequestDTO.builder()
            .labOrderId(orderId)
            .assignmentId(actor.assignmentId())
            .patientId(patient.getId())
            .resultValue(value)
            .resultUnit("mmol/L")
            .resultDate(LocalDateTime.now())
            .build();
        return mockMvc.perform(post(LAB_RESULTS).contextPath(API)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request))
            .with(signedInAs(actor)).with(csrf()));
    }

    private static CriticalValueReadBackRequestDTO readBack(String value) {
        CriticalValueReadBackRequestDTO request = new CriticalValueReadBackRequestDTO();
        request.setRepeatedValue(value);
        return request;
    }

    private LabOrderStatus orderStatus(UUID orderId) {
        return labOrderRepository.findById(orderId).orElseThrow().getStatus();
    }

    /** {@code $.data.<field>} of an ApiResponseWrapper body. */
    private String jsonData(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path(field).asText();
    }

    /** {@code $.id} of a bare DTO body. */
    private UUID idOf(String body) {
        try {
            return UUID.fromString(objectMapper.readTree(body).path("id").asText());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("No id in response body: " + body, ex);
        }
    }

    /** A signed-in user as the JWT filter builds one; the hospital scope resolves from their single assignment. */
    private static RequestPostProcessor signedInAs(Actor actor) {
        CustomUserDetails details = new CustomUserDetails(actor.user().getId(), actor.user().getUsername(), "n/a", true,
            List.of(new SimpleGrantedAuthority(actor.role())));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    // ── seeding ──────────────────────────────────────────────────────────

    private void wipe() {
        auditEventLogRepository.deleteAllInBatch();
        notificationRepository.deleteAll();
        instrumentOutboxRepository.deleteAll();
        labResultRepository.deleteAll();
        labSpecimenRepository.deleteAll();
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

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name)
            .code("H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226555" + id)
            .email("contact" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Actor actor(String prefix, String roleCode, Hospital at) {
        Role role = roleRepository.findByCode(roleCode)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(roleCode.substring("ROLE_".length()))
                .code(roleCode)
                .description(roleCode)
                .build()));
        User user = userRepository.save(buildUser(prefix));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(prefix + " at " + at.getName())
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return new Actor(user, assignment, roleCode);
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

    private Patient createPatient() {
        User patientUser = userRepository.save(buildUser("patient"));
        String suffix = nextId();
        Patient saved = patientRepository.save(Patient.builder()
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
            .patient(saved)
            .hospital(hospital)
            .mrn("MRN-" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
        return saved;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
