package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D819, the exact QA call: as RECEPTIONIST_A, {@code POST /patients} with
 * {@code userId} set to a staff account (her own) was accepted, and her staff
 * login became the new patient's portal login. Now a translated 409 on the
 * {@code userId} field, on the create, on a relink through
 * {@code PUT /patients/{id}}, and for an account that already logs in to
 * another record; a patient-only account is still linked (201).
 */
@AutoConfigureMockMvc(addFilters = false)
class PatientAccountLinkIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;

    private Organization organization;
    private Hospital hospital;
    private User receptionist;
    private User patientAccount;

    @BeforeEach
    void setUp() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
        organization = organizationRepository.save(Organization.builder()
            .name("R13 Network").code("R13ORG-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("R13 Hospital").code("R13H" + nextId()).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226551" + nextId()).email("r13" + nextId() + "@hospital.test")
            .organization(organization).build());

        receptionist = userRepository.save(user("recep"));
        UserRoleHospitalAssignment desk = assignmentRepository.save(assignment(receptionist, role(ROLE_RECEPTIONIST)));
        staffRepository.save(com.example.hms.model.Staff.builder()
            .user(receptionist).hospital(hospital).assignment(desk)
            .jobTitle(JobTitle.values()[0]).employmentType(EmploymentType.FULL_TIME)
            .name("Desk " + receptionist.getFirstName()).active(true).build());

        patientAccount = userRepository.save(user("pat"));
        UserRoleHospitalAssignment patientRow = assignment(patientAccount, role(ROLE_PATIENT));
        patientRow.setActive(false);
        assignmentRepository.save(patientRow);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("the QA call: POST /patients with the receptionist's own staff account is a translated 409, and no record is written")
    void aStaffAccountIsRefused() throws Exception {
        long before = patientRepository.count();

        mockMvc.perform(post(API + "/patients").contextPath(API)
                .header("Accept-Language", "fr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(receptionist.getId()))
                .with(asReceptionist()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.field").value("userId"))
            .andExpect(jsonPath("$.message").value(containsString("membre du personnel")));

        assertThat(patientRepository.count()).isEqualTo(before);
        assertThat(patientRepository.existsByUserId(receptionist.getId())).isFalse();
    }

    @Test
    @DisplayName("a patient-only account is linked (201); a second record for the same account is a 409")
    void aPatientAccountIsLinkedOnce() throws Exception {
        mockMvc.perform(post(API + "/patients").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(patientAccount.getId()))
                .with(asReceptionist()))
            .andExpect(status().isCreated());
        assertThat(patientRepository.existsByUserId(patientAccount.getId())).isTrue();

        mockMvc.perform(post(API + "/patients").contextPath(API)
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(patientAccount.getId()))
                .with(asReceptionist()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.field").value("userId"))
            .andExpect(jsonPath("$.message").value(containsString("already the login of another patient record")));
    }

    @Test
    @DisplayName("PUT /patients/{id} relinking a record to a staff account is a 409; the record keeps its account")
    void aRelinkToAStaffAccountIsRefused() throws Exception {
        mockMvc.perform(post(API + "/patients").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(patientAccount.getId()))
                .with(asReceptionist()))
            .andExpect(status().isCreated());
        Patient created = patientRepository.findByUserId(patientAccount.getId()).orElseThrow();

        mockMvc.perform(put(API + "/patients/{id}", created.getId()).contextPath(API)
                .header("Accept-Language", "es")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(receptionist.getId()))
                .with(asReceptionist()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.field").value("userId"))
            .andExpect(jsonPath("$.message").value(containsString("miembro del personal")));

        assertThat(patientRepository.existsByUserId(receptionist.getId())).isFalse();
        assertThat(patientRepository.findByUserId(patientAccount.getId())).isPresent();
    }

    private String body(UUID userId) {
        return """
            {"firstName":"Rasmané","lastName":"Kaboré","dateOfBirth":"1990-04-02","gender":"M",
             "phoneNumberPrimary":"+226700%s","city":"Ouagadougou","country":"Burkina Faso",
             "hospitalId":"%s","userId":"%s"}
            """.formatted(nextId(), hospital.getId(), userId);
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code.substring(5)).code(code)
                .description(code).build()));
    }

    private UserRoleHospitalAssignment assignment(User user, Role role) {
        return UserRoleHospitalAssignment.builder()
            .assignmentCode("R13-" + nextId()).description("r13").user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder().username("r13" + prefix + suffix).passwordHash("hashed")
            .email("r13" + prefix + suffix + "@example.test").firstName(prefix).lastName("R13" + suffix)
            .phoneNumber("+22676" + suffix).isActive(true).build();
    }

    private RequestPostProcessor asReceptionist() {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST));
        CustomUserDetails principal = new CustomUserDetails(receptionist.getId(), receptionist.getUsername(),
            receptionist.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, receptionist.getPasswordHash(),
            authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(receptionist.getId())
                .principalUsername(receptionist.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(hospital.getId())
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(hospital.getId()))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
