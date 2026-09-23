package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Notification;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit gap B1 end to end: a doctor at hospital A sends a lab order to the
 * laboratory of hospital B. B's scientist sees it, collects the specimen,
 * enters the result and releases it; A's doctor and A's patient read the
 * result; a scientist at an unrelated hospital C gets 404 throughout.
 *
 * <p>Status transitions are deliberately not driven here: the order status
 * lifecycle belongs to fix/lab-order-lifecycle. Only what this change
 * guarantees — visibility, the lab-side writes and the notification — is
 * asserted.
 */
@AutoConfigureMockMvc(addFilters = false)
class LabOrderCrossHospitalIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String JSON_DATA_ID = "$.data.id";

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
    private Hospital hospitalA;
    private Hospital hospitalB;
    private Hospital hospitalC;
    private Actor doctorA;
    private Actor scientistB;
    private Actor scientistC;
    private Patient patient;
    private User patientUser;
    private LabTestDefinition definition;

    private record Actor(User user, Staff staff, UserRoleHospitalAssignment assignment, Hospital hospital, String role) { }

    @BeforeEach
    void setUp() {
        clearRows();

        organization = organizationRepository.save(Organization.builder()
            .name("Cross Hospital Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospitalA = saveHospital("Ordering Hospital A");
        hospitalB = saveHospital("Central Laboratory B");
        hospitalC = saveHospital("Unrelated Clinic C");

        doctorA = actor("doctorA", hospitalA, ROLE_DOCTOR, JobTitle.DOCTOR);
        scientistB = actor("sciB", hospitalB, ROLE_LAB_SCIENTIST, JobTitle.LABORATORY_SCIENTIST);
        scientistC = actor("sciC", hospitalC, ROLE_LAB_SCIENTIST, JobTitle.LABORATORY_SCIENTIST);

        patientUser = userRepository.save(buildUser("patient"));
        patient = createPatient(patientUser, hospitalA);

        definition = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("CBC-" + nextId())
            .name("Complete Blood Count")
            .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        clearRows();
    }

    @Test
    @DisplayName("an order sent to hospital B's laboratory is worked there and read back at hospital A; hospital C sees nothing")
    void orderRoutedToAnotherHospitalsLaboratoryFlowsEndToEnd() throws Exception {
        // 1. The candidate laboratories offered to A's doctor exclude A itself.
        mockMvc.perform(get(API + "/lab-orders/performing-labs").with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem(hospitalB.getId().toString())))
            .andExpect(jsonPath("$.data[*].id", hasItem(hospitalC.getId().toString())))
            .andExpect(jsonPath("$.data[*].id", not(hasItem(hospitalA.getId().toString()))));

        // 2. A's doctor orders, naming B as the performing laboratory.
        String created = mockMvc.perform(post(API + "/lab-orders")
                .with(acting(doctorA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest(hospitalB.getId()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.hospitalId", is(hospitalA.getId().toString())))
            .andExpect(jsonPath("$.data.hospitalName", is("Ordering Hospital A")))
            .andExpect(jsonPath("$.data.performingHospitalId", is(hospitalB.getId().toString())))
            .andExpect(jsonPath("$.data.performingHospitalName", is("Central Laboratory B")))
            .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(created).get("data").get("id").asText());

        // 3. B's lab users were told (after commit, in-app).
        List<Notification> received = notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(scientistB.user().getUsername(), false);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getType()).isEqualTo("LAB_ORDER_RECEIVED");
        assertThat(received.get(0).getMessage()).contains("Ordering Hospital A").contains("Complete Blood Count");
        assertThat(notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(scientistC.user().getUsername(), false)).isEmpty();

        // 4. B sees the order in its worklist, with the ordering hospital named, and by id.
        mockMvc.perform(get(API + "/lab-orders").with(acting(scientistB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].id", is(orderId.toString())))
            .andExpect(jsonPath("$.data.content[0].hospitalName", is("Ordering Hospital A")));
        mockMvc.perform(get(API + "/lab-orders/{id}", orderId).with(acting(scientistB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.performingHospitalName", is("Central Laboratory B")));

        // A keeps its sent-out order, with the performing laboratory named.
        mockMvc.perform(get(API + "/lab-orders").with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].performingHospitalName", is("Central Laboratory B")));

        // C sees nothing: an empty worklist and 404 by id.
        mockMvc.perform(get(API + "/lab-orders").with(acting(scientistC)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(0)));
        mockMvc.perform(get(API + "/lab-orders/{id}", orderId).with(acting(scientistC)))
            .andExpect(status().isNotFound());

        // 5. B collects the specimen; C cannot.
        LabSpecimenRequestDTO specimen = LabSpecimenRequestDTO.builder().specimenType("BLOOD").build();
        mockMvc.perform(post(API + "/lab-orders/{id}/specimens", orderId)
                .with(acting(scientistC))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(specimen)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post(API + "/lab-orders/{id}/specimens", orderId)
                .with(acting(scientistB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(specimen)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.accessionNumber", not(nullValue())));
        mockMvc.perform(get(API + "/lab-orders/{id}/specimens", orderId).with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(get(API + "/lab-orders/{id}/specimens", orderId).with(acting(scientistC)))
            .andExpect(status().isNotFound());

        // 6. B enters the result, judged by its roles at B; C cannot.
        LabResultRequestDTO result = LabResultRequestDTO.builder()
            .labOrderId(orderId)
            .assignmentId(scientistB.assignment().getId())
            .patientId(patient.getId())
            .resultValue("12.4")
            .resultUnit("g/dL")
            .resultDate(LocalDateTime.now())
            .build();
        mockMvc.perform(post(API + "/lab-results")
                .with(acting(scientistC))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(result)))
            .andExpect(status().isNotFound());
        String resultJson = mockMvc.perform(post(API + "/lab-results")
                .with(acting(scientistB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(result)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        // LabResultController returns the bare DTO, not the ApiResponseWrapper.
        UUID resultId = UUID.fromString(objectMapper.readTree(resultJson).get("id").asText());

        // 7. B releases it (a normal value may already be auto-verified; the
        //    call must succeed at B and be refused at C either way).
        mockMvc.perform(post(API + "/lab-results/{id}/release", resultId).with(acting(scientistC)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post(API + "/lab-results/{id}/release", resultId).with(acting(scientistB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.released", is(true)));

        // 8. A's doctor reads the order and the result; C cannot read the result.
        mockMvc.perform(get(API + "/lab-orders/{id}", orderId).with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath(JSON_DATA_ID, is(orderId.toString())));
        mockMvc.perform(get(API + "/lab-results/{id}", resultId).with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultValue", is("12.4")));
        mockMvc.perform(get(API + "/lab-results/{id}", resultId).with(acting(scientistB)))
            .andExpect(status().isOk());
        mockMvc.perform(get(API + "/lab-results/{id}", resultId).with(acting(scientistC)))
            .andExpect(status().isNotFound());

        // 9. The patient at A sees the result on the portal.
        mockMvc.perform(get(API + "/me/patient/lab-results").with(actingPatient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem(resultId.toString())));

        // The row itself says who performs it. Its status is #716's business,
        // not this PR's: releasing the last result now completes the order,
        // so the lifecycle is asserted here only to record that the
        // performing laboratory drives it exactly as the ordering one would.
        assertThat(labOrderRepository.findById(orderId))
            .get()
            .satisfies(order -> {
                assertThat(order.getPerformingHospital().getId()).isEqualTo(hospitalB.getId());
                assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
            });
    }

    @Test
    @DisplayName("the worklist search keeps in-house orders alongside outsourced ones, on both sides")
    void searchReturnsInHouseAndOutsourcedOrdersFromBothSides() throws Exception {
        // The widened predicate ORs a nullable association into the criteria
        // query, and an implicit INNER join on performing_hospital_id would
        // silently drop every in-house order — the common case — from the
        // worklist. Only a real database can answer that, so both kinds of
        // order are created here and counted from each hospital.
        UUID inHouse = createOrder(doctorA, orderRequest(null));
        UUID outsourced = createOrder(doctorA, orderRequest(hospitalB.getId()));

        assertThat(labOrderRepository.findById(inHouse))
            .get().extracting(LabOrder::getPerformingHospital).isNull();

        // The ordering hospital sees both.
        mockMvc.perform(get(API + "/lab-orders").with(acting(doctorA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(2)))
            .andExpect(jsonPath("$.data.content[*].id",
                containsInAnyOrder(inHouse.toString(), outsourced.toString())));

        // The performing laboratory sees the one sent to it, and only that one.
        mockMvc.perform(get(API + "/lab-orders").with(acting(scientistB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].id", is(outsourced.toString())));

        // A hospital on neither side of either order sees nothing.
        mockMvc.perform(get(API + "/lab-orders").with(acting(scientistC)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(0)));

        // The JPQL finders navigate the same nullable association, so they are
        // asked the same question directly — they have no HTTP surface of
        // their own on this controller.
        assertThat(labOrderRepository.findHandledBy(hospitalA.getId()))
            .extracting(LabOrder::getId).containsExactlyInAnyOrder(inHouse, outsourced);
        assertThat(labOrderRepository.findHandledBy(hospitalB.getId()))
            .extracting(LabOrder::getId).containsExactly(outsourced);
        assertThat(labOrderRepository.findByStatusHandledBy(LabOrderStatus.ORDERED, hospitalA.getId()))
            .extracting(LabOrder::getId).containsExactlyInAnyOrder(inHouse, outsourced);
        assertThat(labOrderRepository.findByPatientIdReadableOrPerformedAt(
                patient.getId(), Set.of(hospitalA.getId()), hospitalA.getId()))
            .extracting(LabOrder::getId).containsExactlyInAnyOrder(inHouse, outsourced);
        assertThat(labOrderRepository.findByPatientIdReadableOrPerformedAt(
                patient.getId(), Set.of(hospitalB.getId()), hospitalB.getId()))
            .extracting(LabOrder::getId).containsExactly(outsourced);
    }

    @Test
    @DisplayName("naming the ordering hospital itself as performing laboratory is an in-house order")
    void performingHospitalEqualToOrderingHospitalIsInHouse() throws Exception {
        mockMvc.perform(post(API + "/lab-orders")
                .with(acting(doctorA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest(hospitalA.getId()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.performingHospitalId", nullValue()));

        assertThat(notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(scientistB.user().getUsername(), false)).isEmpty();
    }

    @Test
    @DisplayName("an inactive hospital cannot be named as performing laboratory")
    void inactivePerformingLaboratoryIsRefused() throws Exception {
        hospitalC.setActive(false);
        hospitalRepository.save(hospitalC);

        mockMvc.perform(post(API + "/lab-orders")
                .with(acting(doctorA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest(hospitalC.getId()))))
            .andExpect(status().isBadRequest());
        assertThat(labOrderRepository.count()).isZero();
    }

    private UUID createOrder(Actor actor, LabOrderRequestDTO request) throws Exception {
        String body = mockMvc.perform(post(API + "/lab-orders")
                .with(acting(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("data").get("id").asText());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private LabOrderRequestDTO orderRequest(UUID performingHospitalId) {
        return LabOrderRequestDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospitalA.getId())
            .performingHospitalId(performingHospitalId)
            .orderingStaffId(doctorA.staff().getId())
            .labTestDefinitionId(definition.getId())
            .assignmentId(doctorA.assignment().getId())
            .testName("Complete Blood Count")
            .status(LabOrderStatus.ORDERED.name())
            .clinicalIndication("Fatigue and pallor")
            .medicalNecessityNote("Rule out anaemia")
            .primaryDiagnosisCode("D64.9")
            .orderChannel(LabOrderChannel.ELECTRONIC.name())
            .documentationSharedWithLab(true)
            .providerSignature("signed-by-doctor-a")
            .standingOrder(false)
            .orderDatetime(LocalDateTime.now().minusMinutes(sequence.incrementAndGet()))
            .build();
    }

    private void clearRows() {
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

    private Actor actor(String prefix, Hospital hospital, String roleCode, JobTitle jobTitle) {
        Role role = ensureRole(roleCode);
        User user = userRepository.save(buildUser(prefix));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(roleCode + " at " + hospital.getName())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        Staff staff = staffRepository.save(Staff.builder()
            .user(user)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(jobTitle)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name(prefix)
            .active(true)
            .build());
        return new Actor(user, staff, assignment, hospital, roleCode);
    }

    private Role ensureRole(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(code.substring("ROLE_".length()))
                .code(code)
                .description(code)
                .build()));
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

    private Patient createPatient(User user, Hospital hospital) {
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
            .user(user)
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

    private RequestPostProcessor acting(Actor actor) {
        return request -> {
            Authentication auth = authenticate(actor.user(), actor.role());
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(actor.user().getId())
                .principalUsername(actor.user().getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(actor.hospital().getId())
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(actor.hospital().getId()))
                .build());
            request.setContextPath(API);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private RequestPostProcessor actingPatient() {
        return request -> {
            Authentication auth = authenticate(patientUser, ROLE_PATIENT);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(patientUser.getId())
                .principalUsername(patientUser.getUsername())
                .build());
            request.setContextPath(API);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private static Authentication authenticate(User user, String role) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        CustomUserDetails principal = new CustomUserDetails(
            user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
