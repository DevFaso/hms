package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D921, end to end on a real persistence context: the emailed activation
 * link ({@code GET /api/auth/verify-email}) answered 500 every time and left
 * the account active, its token consumed, and its ROLE_PATIENT assignment
 * still off. The controller read the assignment's LAZY role with no session
 * (open-in-view is off) after the account save had already committed. Now the
 * whole activation runs in one transaction: 200, the account AND the patient
 * assignment are on, and the token is gone.
 */
@AutoConfigureMockMvc(addFilters = false)
class EmailVerificationActivationIT extends BaseIT {

    private static final String API = "/api";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private Hospital hospital;
    private User account;
    private UserRoleHospitalAssignment patientRow;

    @BeforeEach
    void setUp() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
        Organization organization = organizationRepository.save(Organization.builder()
            .name("R16 Network").code("R16ORG-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("R16 Hospital").code("R16H" + nextId()).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226561" + nextId()).email("r16" + nextId() + "@hospital.test")
            .organization(organization).build());

        String suffix = nextId();
        account = userRepository.save(User.builder().username("r16pat" + suffix).passwordHash("hashed")
            .email("r16pat" + suffix + "@example.test").firstName("Mariam").lastName("Sawadogo" + suffix)
            .phoneNumber("+22677" + suffix).isActive(false)
            .activationToken("tok-" + suffix).activationTokenExpiresAt(LocalDateTime.now().plusHours(4))
            .build());
        patientRow = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("R16-" + nextId()).description("r16").user(account).hospital(hospital)
            .role(role("ROLE_PATIENT")).startDate(LocalDate.now()).assignedAt(LocalDateTime.now())
            .active(false).build());
    }

    @AfterEach
    void tearDown() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("the QA call: the emailed link answers 200 and switches on the account AND its patient role")
    void theLinkActivatesTheAccountAndThePatientRole() throws Exception {
        mockMvc.perform(get(API + "/auth/verify-email").contextPath(API)
                .param("email", account.getEmail())
                .param("token", account.getActivationToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));

        User reloaded = userRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getActivationToken()).isNull();
        assertThat(assignmentRepository.findById(patientRow.getId()).orElseThrow().getActive()).isTrue();
    }

    @Test
    @DisplayName("a wrong token is a 400 and changes nothing")
    void aWrongTokenChangesNothing() throws Exception {
        mockMvc.perform(get(API + "/auth/verify-email").contextPath(API)
                .param("email", account.getEmail())
                .param("token", "not-the-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.verified").value(false));

        User reloaded = userRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.getActivationToken()).isNotNull();
        assertThat(assignmentRepository.findById(patientRow.getId()).orElseThrow().getActive()).isFalse();
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code.substring(5)).code(code)
                .description(code).build()));
    }

    private String nextId() {
        return String.valueOf(System.nanoTime() % 1_000_000 + sequence.incrementAndGet());
    }
}
