package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Notification;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PrescriptionClarificationRequestDTO;
import com.example.hms.payload.dto.PrescriptionClarificationResolutionDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G5 end to end: the pharmacist's clarification request and the doctor's
 * answer, over HTTP with the real transaction manager — so the after-commit
 * notification (G6) is observed as a row, not as a mock interaction.
 */
@AutoConfigureMockMvc(addFilters = false)
class PrescriptionClarificationIT extends BaseIT {

    private static final String API_CONTEXT = "/api";
    @SuppressWarnings("java:S1075")
    private static final String REQUEST_PATH = "/prescriptions/{id}/request-clarification";
    @SuppressWarnings("java:S1075")
    private static final String RESOLVE_PATH = "/prescriptions/{id}/resolve-clarification";
    @SuppressWarnings("java:S1075")
    private static final String DISPENSE_PATH = "/pharmacy/dispense";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_PHARMACIST = "ROLE_PHARMACIST";
    private static final String REASON = "Dose au-dessus du plafond rénal pour cette clairance";

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
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Organization organization;
    private Hospital hospital;
    private Hospital otherHospital;
    private User doctorUser;
    private User pharmacistUser;
    private Prescription prescription;

    private Patient patient;
    private User patientUser;
    private Staff doctorStaff;
    private Encounter encounter;
    private final java.util.List<UserRoleHospitalAssignment> assignments = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Clarification Health Network")
            .code("ORG-CLR-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = saveHospital("Clarification Teaching Hospital");
        otherHospital = saveHospital("Clarification District Hospital");

        Role doctorRole = ensureRole(ROLE_DOCTOR, "Doctor");
        Role pharmacistRole = ensureRole(ROLE_PHARMACIST, "Pharmacist");

        doctorUser = userRepository.save(buildUser("doctor"));
        UserRoleHospitalAssignment doctorAssignment = saveAssignment(doctorUser, doctorRole, hospital);
        doctorStaff = staffRepository.save(Staff.builder()
            .user(doctorUser)
            .hospital(hospital)
            .assignment(doctorAssignment)
            .jobTitle(JobTitle.PHYSICIAN)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr. " + doctorUser.getFirstName())
            .active(true)
            .build());

        pharmacistUser = userRepository.save(buildUser("pharmacist"));
        saveAssignment(pharmacistUser, pharmacistRole, hospital);

        patient = savePatient();
        encounter = encounterRepository.save(Encounter.builder()
            .patient(patient)
            .staff(doctorStaff)
            .hospital(hospital)
            .assignment(doctorAssignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .code("ENC-" + nextId())
            .build());

        prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patient)
            .staff(doctorStaff)
            .encounter(encounter)
            .hospital(hospital)
            .assignment(doctorAssignment)
            .medicationName("Amoxicilline 500 mg")
            .quantity(BigDecimal.TEN)
            .status(PrescriptionStatus.SIGNED)
            .build());
    }

    /**
     * The H2 database is shared with the sibling ITs, and some of them leave
     * rows behind that reference staff and hospitals; a table-wide delete
     * here would trip their foreign keys. Remove only what this class made,
     * children first.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        notificationRepository.deleteAllInBatch();
        auditEventLogRepository.deleteAllInBatch();
        prescriptionRepository.deleteById(prescription.getId());
        encounterRepository.deleteById(encounter.getId());
        patientRepository.deleteById(patient.getId());
        staffRepository.deleteById(doctorStaff.getId());
        assignments.forEach(a -> assignmentRepository.deleteById(a.getId()));
        for (User u : List.of(doctorUser, pharmacistUser, patientUser)) {
            userRepository.deleteById(u.getId());
        }
        hospitalRepository.deleteById(hospital.getId());
        hospitalRepository.deleteById(otherHospital.getId());
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    @DisplayName("a pharmacist's request moves the order to PENDING_CLARIFICATION, audits without the reason, and notifies the prescriber after commit")
    void pharmacistRequestsClarification() throws Exception {
        mockMvc.perform(post(API_CONTEXT + REQUEST_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                PrescriptionClarificationRequestDTO.builder().reason(REASON).build()))
            .accept(MediaType.APPLICATION_JSON)
            .with(as(pharmacistUser, ROLE_PHARMACIST, hospital)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_CLARIFICATION"))
            .andExpect(jsonPath("$.clarificationReason").value(REASON))
            .andExpect(jsonPath("$.lastPharmacyEvent").value("PENDING_CLARIFICATION"));

        Prescription stored = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PrescriptionStatus.PENDING_CLARIFICATION);
        assertThat(stored.getClarificationReason()).isEqualTo(REASON);
        assertThat(stored.getClarificationRequestedByUserId()).isEqualTo(pharmacistUser.getId());

        // G6: the after-commit writer ran on this thread once the request's
        // transaction committed — the row exists, addressed to the prescriber.
        List<Notification> forDoctor = notificationRepository
            .findByRecipientUsernameAndTypeAndReadFalseOrderByCreatedAtDesc(
                doctorUser.getUsername(), "PHARMACY_EVENT");
        assertThat(forDoctor).hasSize(1);
        assertThat(forDoctor.get(0).getMessage())
            .contains("Amoxicilline 500 mg")
            .contains(REASON);

        List<AuditEventLog> audits = auditEventLogRepository.findAll().stream()
            .filter(row -> row.getEventType() == AuditEventType.PRESCRIPTION_CLARIFICATION_REQUESTED)
            .toList();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getEventDescription()).doesNotContain(REASON);
    }

    @Test
    @DisplayName("a doctor cannot call the pharmacist's endpoint (403)")
    void doctorCannotRequestClarification() throws Exception {
        mockMvc.perform(post(API_CONTEXT + REQUEST_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                PrescriptionClarificationRequestDTO.builder().reason(REASON).build()))
            .with(as(doctorUser, ROLE_DOCTOR, hospital)))
            .andExpect(status().isForbidden());

        assertThat(prescriptionRepository.findById(prescription.getId()).orElseThrow().getStatus())
            .isEqualTo(PrescriptionStatus.SIGNED);
    }

    @Test
    @DisplayName("a blank reason is rejected at the boundary (400)")
    void blankReasonIsRejected() throws Exception {
        mockMvc.perform(post(API_CONTEXT + REQUEST_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                PrescriptionClarificationRequestDTO.builder().reason("   ").build()))
            .with(as(pharmacistUser, ROLE_PHARMACIST, hospital)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a pharmacist scoped to another hospital gets 404, not 403")
    void crossTenantRequestIs404() throws Exception {
        mockMvc.perform(post(API_CONTEXT + REQUEST_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                PrescriptionClarificationRequestDTO.builder().reason(REASON).build()))
            .with(as(pharmacistUser, ROLE_PHARMACIST, otherHospital)))
            .andExpect(status().isNotFound());

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("a prescription awaiting clarification is not dispensable")
    void pendingClarificationIsNotDispensable() throws Exception {
        prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
        prescription.setClarificationReason(REASON);
        prescriptionRepository.save(prescription);

        DispenseRequestDTO dispense = DispenseRequestDTO.builder()
            .prescriptionId(prescription.getId())
            .patientId(prescription.getPatient().getId())
            .pharmacyId(java.util.UUID.randomUUID())
            .dispensedBy(pharmacistUser.getId())
            .medicationName("Amoxicilline 500 mg")
            .quantityRequested(BigDecimal.TEN)
            .quantityDispensed(BigDecimal.TEN)
            .build();

        mockMvc.perform(post(API_CONTEXT + DISPENSE_PATH)
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(dispense))
            .with(as(pharmacistUser, ROLE_PHARMACIST, hospital)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the prescribing doctor's answer returns the order to SIGNED with the exchange on record")
    void doctorResolvesClarification() throws Exception {
        prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
        prescription.setClarificationReason(REASON);
        prescription.setClarificationRequestedAt(LocalDateTime.now());
        prescription.setClarificationRequestedByUserId(pharmacistUser.getId());
        prescriptionRepository.save(prescription);

        mockMvc.perform(post(API_CONTEXT + RESOLVE_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                PrescriptionClarificationResolutionDTO.builder().response("Clairance vérifiée, dose confirmée").build()))
            .accept(MediaType.APPLICATION_JSON)
            .with(as(doctorUser, ROLE_DOCTOR, hospital)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SIGNED"))
            .andExpect(jsonPath("$.clarificationReason").value(REASON))
            .andExpect(jsonPath("$.clarificationResponse").value("Clairance vérifiée, dose confirmée"))
            .andExpect(jsonPath("$.clarificationResolvedAt").exists());

        Prescription stored = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        assertThat(stored.getClarificationResolvedByUserId()).isEqualTo(doctorUser.getId());
        assertThat(auditEventLogRepository.findAll())
            .anyMatch(row -> row.getEventType() == AuditEventType.PRESCRIPTION_CLARIFICATION_RESOLVED);
    }

    @Test
    @DisplayName("a pharmacist cannot resolve a clarification (403)")
    void pharmacistCannotResolve() throws Exception {
        prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
        prescriptionRepository.save(prescription);

        mockMvc.perform(post(API_CONTEXT + RESOLVE_PATH, prescription.getId())
            .contextPath(API_CONTEXT)
            .with(as(pharmacistUser, ROLE_PHARMACIST, hospital)))
            .andExpect(status().isForbidden());
    }

    /* ---------------------------------------------------------------- fixtures */

    private Hospital saveHospital(String name) {
        String n = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name)
            .code("HCL" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226555" + n)
            .email("clr" + n + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Role ensureRole(String code, String name) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .code(code)
                .description(name + " role")
                .build()));
    }

    private UserRoleHospitalAssignment saveAssignment(User user, Role role, Hospital at) {
        UserRoleHospitalAssignment saved = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(role.getName() + " assignment")
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        assignments.add(saved);
        return saved;
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + "-clr-" + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@clarification.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build();
    }

    private Patient savePatient() {
        patientUser = userRepository.save(buildUser("patient"));
        String suffix = nextId();
        return patientRepository.save(Patient.builder()
            .firstName("Aminata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("aminata" + suffix + "@patient.test")
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .user(patientUser)
            .build());
    }

    private RequestPostProcessor as(User user, String role, Hospital at) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        CustomUserDetails principal = new CustomUserDetails(
            user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(user.getId())
                .principalUsername(user.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(at.getId())
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(hospital.getId(), otherHospital.getId()))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
