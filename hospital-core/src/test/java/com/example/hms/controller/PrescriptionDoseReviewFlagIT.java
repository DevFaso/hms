package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1142 over HTTP with the real transaction manager: an infant order saved
 * before the paediatric dose-review gate is refused at its signature, and the
 * review requirement that refusal discovered is still on the order afterwards
 * — so the reviewer's "Record the dose review" action appears. Before the fix
 * the refusal rolled the flag back and the UI offered no way out.
 */
@AutoConfigureMockMvc(addFilters = false)
class PrescriptionDoseReviewFlagIT extends BaseIT {

    private static final String API_CONTEXT = "/api";
    @SuppressWarnings("java:S1075")
    private static final String SIGN_PATH = "/prescriptions/{id}/sign";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

    private final AtomicInteger sequence = new AtomicInteger();
    private final List<UserRoleHospitalAssignment> assignments = new ArrayList<>();

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;

    private Organization organization;
    private Hospital hospital;
    private User doctorUser;
    private User patientUser;
    private Staff doctorStaff;
    private Patient patient;
    private Encounter encounter;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Dose Review Network")
            .code("ORG-DRV-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Dose Review Hospital")
            .code("HDR" + nextId())
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226556" + nextId())
            .email("drv" + nextId() + "@hospital.test")
            .organization(organization)
            .build());
        Role doctorRole = roleRepository.findByCode(ROLE_DOCTOR)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("Doctor").code(ROLE_DOCTOR).description("Doctor role").build()));

        doctorUser = userRepository.save(buildUser("doctor"));
        UserRoleHospitalAssignment doctorAssignment = assignmentRepository.save(
            UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-" + nextId())
                .description("Doctor assignment")
                .user(doctorUser)
                .hospital(hospital)
                .role(doctorRole)
                .startDate(LocalDate.now())
                .assignedAt(LocalDateTime.now())
                .active(true)
                .build());
        assignments.add(doctorAssignment);
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

        patientUser = userRepository.save(buildUser("patient"));
        patient = patientRepository.save(Patient.builder()
            .firstName("Wendpanga")
            .lastName("Ouedraogo")
            .dateOfBirth(LocalDate.now().minusWeeks(5))
            .gender("M")
            .address("Patient address")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22679" + nextId())
            .email("wend" + nextId() + "@patient.test")
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .user(patientUser)
            .build());
        encounter = encounterRepository.save(Encounter.builder()
            .patient(patient)
            .staff(doctorStaff)
            .hospital(hospital)
            .assignment(doctorAssignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .code("ENC-" + nextId())
            .build());

        // An order saved before the round-19 gate: no review requirement on it.
        // The real dose check (no Spring bean is replaced, so this shares the
        // suite's context) asks for a review under 3 months
        // (PediatricDoseRule.DEFAULT_MANUAL_REVIEW_UNDER_MONTHS) when no
        // catalogue item says otherwise.
        prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patient)
            .staff(doctorStaff)
            .encounter(encounter)
            .hospital(hospital)
            .assignment(doctorAssignment)
            .medicationName("Paracétamol sirop 120 mg/5 ml")
            .dosage("2.5 ml")
            .frequency("q6h")
            .quantity(BigDecimal.ONE)
            .status(PrescriptionStatus.DRAFT)
            .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        prescriptionRepository.deleteById(prescription.getId());
        encounterRepository.deleteById(encounter.getId());
        patientRepository.deleteById(patient.getId());
        staffRepository.deleteById(doctorStaff.getId());
        assignments.forEach(a -> assignmentRepository.deleteById(a.getId()));
        userRepository.deleteById(doctorUser.getId());
        userRepository.deleteById(patientUser.getId());
        hospitalRepository.deleteById(hospital.getId());
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    @DisplayName("QA D1142: the refused signature keeps the review requirement it discovered")
    void refusedSignatureKeepsTheReviewFlag() throws Exception {
        assertThat(prescription.isPediatricDoseReviewRequired()).isFalse();

        mockMvc.perform(post(API_CONTEXT + SIGN_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(doctorUser)))
            .andExpect(status().isBadRequest());

        Prescription stored = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(stored.isPediatricDoseReviewRequired())
            .as("the requirement outlives the refusal, so the review can be recorded from the UI")
            .isTrue();
        assertThat(stored.getStatus()).isEqualTo(PrescriptionStatus.DRAFT);
        assertThat(stored.getSignatureValue()).isNull();
        assertThat(stored.getPediatricDoseReviewedAt()).isNull();
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + "-drv-" + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@dosereview.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22676" + suffix)
            .isActive(true)
            .build();
    }

    private RequestPostProcessor as(User user) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
        CustomUserDetails principal = new CustomUserDetails(
            user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        UUID hospitalId = hospital.getId();
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(user.getId())
                .principalUsername(user.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(hospitalId)
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(hospitalId))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
