package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderSignatureRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Imaging-order writes are held to the acting hospital, like the read.
 *
 * <p>The repository tenant filter used to OR in every organisation the caller
 * has an assignment under, so a doctor at hospital A loaded an order of
 * sibling hospital A2 through the scoped {@code findById}, and the three
 * writes went ahead. The filter is now hospitals only
 * (docs/security/tenant-resolution.md Q6, option A); the service checks stay
 * as the second gate, and both are asked of a real database.
 */
@AutoConfigureMockMvc(addFilters = false)
class ImagingOrderSiblingHospitalWriteIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private ImagingOrderRepository imagingOrderRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalA2;
    private User doctorA;
    private Patient patient;
    private Patient siblingOnlyPatient;
    private ImagingOrder orderAtA;
    private ImagingOrder orderAtA2;

    @BeforeEach
    void setUp() {
        clearRows();
        organization = organizationRepository.save(Organization.builder()
            .name("Sibling Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospitalA = saveHospital("Hospital A");
        hospitalA2 = saveHospital("Sibling Hospital A2");
        doctorA = doctorAt(hospitalA);
        patient = createPatient(hospitalA);
        siblingOnlyPatient = createPatient(hospitalA2);
        orderAtA = saveOrder(hospitalA);
        orderAtA2 = saveOrder(hospitalA2);
        HospitalContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        clearRows();
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("the repository filter no longer hands A's doctor the sibling's order (Q6 A)")
    void organisationGrantsNoReadOfTheSiblingsOrder() {
        // The organisation disjunct is gone: with A's context the scoped
        // findById does not answer for A2's order. The service checks below
        // remain the second gate.
        HospitalContextHolder.setContext(contextFor(doctorA, hospitalA));
        assertThat(imagingOrderRepository.findById(orderAtA2.getId())).isEmpty();
    }

    @Test
    @DisplayName("A's doctor cannot edit A2's order: answered exactly as a missing id")
    void updateOfSiblingOrderAnswersLikeMissing() throws Exception {
        String body = objectMapper.writeValueAsString(updateRequest(hospitalA2.getId(), "Edited from A"));
        assertSameAsMissing(id -> put(API + "/imaging/orders/{id}", id)
            .contentType(MediaType.APPLICATION_JSON).content(body));

        ImagingOrder untouched = reload(orderAtA2);
        assertThat(untouched.getStudyType()).isEqualTo("Chest PA");
        assertThat(untouched.getHospital().getId()).isEqualTo(hospitalA2.getId());
    }

    @Test
    @DisplayName("A's doctor cannot re-status A2's order: answered exactly as a missing id")
    void statusOfSiblingOrderAnswersLikeMissing() throws Exception {
        ImagingOrderStatusUpdateRequestDTO request = new ImagingOrderStatusUpdateRequestDTO();
        request.setStatus(ImagingOrderStatus.CANCELLED);
        request.setCancellationReason("cancelled from A");
        String body = objectMapper.writeValueAsString(request);
        assertSameAsMissing(id -> put(API + "/imaging/orders/{id}/status", id)
            .contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(reload(orderAtA2).getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
    }

    @Test
    @DisplayName("A's doctor cannot sign A2's order: answered exactly as a missing id")
    void signatureOnSiblingOrderAnswersLikeMissing() throws Exception {
        String body = objectMapper.writeValueAsString(signatureRequest());
        assertSameAsMissing(id -> post(API + "/imaging/orders/{id}/signature", id)
            .contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(reload(orderAtA2).getProviderSignedAt()).isNull();
    }

    @Test
    @DisplayName("an order at A cannot be moved to A2: answered exactly as a missing hospital")
    void orderCannotBeMovedToAnotherHospital() throws Exception {
        MvcResult toSibling = mockMvc.perform(put(API + "/imaging/orders/{id}", orderAtA.getId())
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest(hospitalA2.getId(), "Moved"))))
            .andExpect(status().isNotFound())
            .andReturn();
        UUID missingHospital = UUID.randomUUID();
        MvcResult toMissing = mockMvc.perform(put(API + "/imaging/orders/{id}", orderAtA.getId())
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest(missingHospital, "Moved"))))
            .andExpect(status().isNotFound())
            .andReturn();
        assertThat(message(toSibling).replace(hospitalA2.getId().toString(), "<id>"))
            .isEqualTo(message(toMissing).replace(missingHospital.toString(), "<id>"));

        ImagingOrder stayed = reload(orderAtA);
        assertThat(stayed.getHospital().getId()).isEqualTo(hospitalA.getId());
        assertThat(stayed.getStudyType()).isEqualTo("Chest PA");
    }

    @Test
    @DisplayName("A's doctor cannot create an order at A2")
    void createAtSiblingIsRefused() throws Exception {
        ImagingOrderRequestDTO request = updateRequest(hospitalA2.getId(), "Created for A2");
        request.setPatientId(patient.getId());
        mockMvc.perform(post(API + "/imaging/orders")
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());
        assertThat(imagingOrderRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("A's doctor cannot order for, or re-point an order at, a patient registered only at A2")
    void patientRegisteredOnlyAtSiblingAnswersLikeMissing() throws Exception {
        // The organisation grants no read (Q6 A): the scoped finder does not
        // hand A's doctor that patient; the service refuses it as well.
        HospitalContextHolder.setContext(contextFor(doctorA, hospitalA));
        assertThat(patientRepository.findById(siblingOnlyPatient.getId())).isEmpty();
        HospitalContextHolder.clear();

        UUID missingPatient = UUID.randomUUID();
        for (boolean create : new boolean[] {true, false}) {
            MvcResult sibling = mockMvc.perform(orderFor(create, siblingOnlyPatient.getId()))
                .andExpect(status().isNotFound())
                .andReturn();
            MvcResult absent = mockMvc.perform(orderFor(create, missingPatient))
                .andExpect(status().isNotFound())
                .andReturn();
            assertThat(message(sibling).replace(siblingOnlyPatient.getId().toString(), "<id>"))
                .isEqualTo(message(absent).replace(missingPatient.toString(), "<id>"));
        }

        assertThat(imagingOrderRepository.count()).isEqualTo(2);
        assertThat(reload(orderAtA).getPatient().getId()).isEqualTo(patient.getId());
    }

    private MockHttpServletRequestBuilder orderFor(boolean create, UUID patientId) throws Exception {
        ImagingOrderRequestDTO request = updateRequest(hospitalA.getId(), "Chest PA");
        request.setPatientId(patientId);
        MockHttpServletRequestBuilder builder = create
            ? post(API + "/imaging/orders")
            : put(API + "/imaging/orders/{id}", orderAtA.getId());
        return builder.with(acting(doctorA, hospitalA))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request));
    }

    @Test
    @DisplayName("at its own hospital the doctor still creates, edits, re-statuses and signs")
    void inHospitalFlowStillWorks() throws Exception {
        ImagingOrderRequestDTO create = updateRequest(hospitalA.getId(), "Abdomen US");
        mockMvc.perform(post(API + "/imaging/orders")
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.hospitalId", is(hospitalA.getId().toString())));

        mockMvc.perform(put(API + "/imaging/orders/{id}", orderAtA.getId())
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest(hospitalA.getId(), "Chest PA and lateral"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.studyType", is("Chest PA and lateral")));

        ImagingOrderStatusUpdateRequestDTO scheduled = new ImagingOrderStatusUpdateRequestDTO();
        scheduled.setStatus(ImagingOrderStatus.SCHEDULED);
        mockMvc.perform(put(API + "/imaging/orders/{id}/status", orderAtA.getId())
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(scheduled)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("SCHEDULED")));

        mockMvc.perform(post(API + "/imaging/orders/{id}/signature", orderAtA.getId())
                .with(acting(doctorA, hospitalA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signatureRequest())))
            .andExpect(status().isOk());

        ImagingOrder saved = reload(orderAtA);
        assertThat(saved.getStudyType()).isEqualTo("Chest PA and lateral");
        assertThat(saved.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);
        assertThat(saved.getProviderSignedAt()).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RequestFor {
        MockHttpServletRequestBuilder apply(UUID orderId);
    }

    /** The sibling's order and an id that matches no row get the same 404 and the same message. */
    private void assertSameAsMissing(RequestFor request) throws Exception {
        MvcResult sibling = mockMvc.perform(request.apply(orderAtA2.getId()).with(acting(doctorA, hospitalA)))
            .andExpect(status().isNotFound())
            .andReturn();
        UUID missing = UUID.randomUUID();
        MvcResult absent = mockMvc.perform(request.apply(missing).with(acting(doctorA, hospitalA)))
            .andExpect(status().isNotFound())
            .andReturn();
        assertThat(message(sibling).replace(orderAtA2.getId().toString(), "<id>"))
            .isEqualTo(message(absent).replace(missing.toString(), "<id>"));
    }

    private String message(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asText();
    }

    private ImagingOrder reload(ImagingOrder order) {
        asUnscopedFixture();
        return imagingOrderRepository.findById(order.getId()).orElseThrow();
    }

    /** Fixture reads and clean-up run unfiltered, so the tenant filter never hides a row from them. */
    private static void asUnscopedFixture() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
    }

    private ImagingOrderRequestDTO updateRequest(UUID hospitalId, String studyType) {
        ImagingOrderRequestDTO request = new ImagingOrderRequestDTO();
        request.setPatientId(patient.getId());
        request.setHospitalId(hospitalId);
        request.setModality(ImagingModality.XRAY);
        request.setStudyType(studyType);
        request.setPriority(ImagingOrderPriority.ROUTINE);
        return request;
    }

    private ImagingOrderSignatureRequestDTO signatureRequest() {
        ImagingOrderSignatureRequestDTO request = new ImagingOrderSignatureRequestDTO();
        request.setProviderName("Doctor A");
        request.setProviderUserId(doctorA.getId());
        request.setSignatureStatement("I attest this order is medically necessary.");
        request.setAttestationConfirmed(true);
        return request;
    }

    private ImagingOrder saveOrder(Hospital hospital) {
        return imagingOrderRepository.save(ImagingOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .modality(ImagingModality.XRAY)
            .studyType("Chest PA")
            .priority(ImagingOrderPriority.ROUTINE)
            .status(ImagingOrderStatus.ORDERED)
            .orderedAt(LocalDateTime.now())
            .build());
    }

    private HospitalContext contextFor(User user, Hospital hospital) {
        // The password-login shape: permitted organisations are filled from
        // each assignment's hospital's organisation, so A2 rides in on the
        // organisation disjunct although only A is a permitted hospital.
        return HospitalContext.builder()
            .principalUserId(user.getId())
            .principalUsername(user.getUsername())
            .activeOrganizationId(organization.getId())
            .activeHospitalId(hospital.getId())
            .permittedOrganizationIds(Set.of(organization.getId()))
            .permittedHospitalIds(Set.of(hospital.getId()))
            .build();
    }

    private RequestPostProcessor acting(User user, Hospital hospital) {
        return request -> {
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
            CustomUserDetails principal = new CustomUserDetails(
                user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(contextFor(user, hospital));
            request.setContextPath(API);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private void clearRows() {
        asUnscopedFixture();
        auditEventLogRepository.deleteAllInBatch();
        imagingOrderRepository.deleteAll();
        registrationRepository.deleteAll();
        patientRepository.deleteAll();
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

    private User doctorAt(Hospital hospital) {
        Role role = roleRepository.findByCode(ROLE_DOCTOR)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("DOCTOR").code(ROLE_DOCTOR).description(ROLE_DOCTOR).build()));
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username("doctorA" + suffix)
            .passwordHash("hashed-password")
            .email("doctorA" + suffix + "@example.test")
            .firstName("Doctor")
            .lastName("A" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build());
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description("doctor at " + hospital.getName())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return user;
    }

    private Patient createPatient(Hospital registeredAt) {
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
            .hospitalId(registeredAt.getId())
            .user(userRepository.save(User.builder()
                .username("patient" + suffix)
                .passwordHash("hashed-password")
                .email("patient" + suffix + "@example.test")
                .firstName("Patient")
                .lastName("P" + suffix)
                .phoneNumber("+22676" + suffix)
                .isActive(true)
                .build()))
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved)
            .hospital(registeredAt)
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
