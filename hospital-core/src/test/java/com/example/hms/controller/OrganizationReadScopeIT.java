package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D845: HOSPITAL_ADMIN_B (Clinique Naba-Koom, organisation RSF) called
 * GET /api/organizations and read all three organisations, contacts, onboarding
 * notes and security policies included; GET /api/organizations/{DEFAULT_ORG}
 * answered 200 too. A hospital administrator now reads only the organisations
 * of the hospitals they administer; a verified super-admin still reads every one.
 * Run on the real database, so the organisation query is the one that ships.
 */
@AutoConfigureMockMvc(addFilters = false)
class OrganizationReadScopeIT extends BaseIT {

    private static final String API = "/api";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private OrganizationSecurityPolicyRepository policyRepository;
    @Autowired private OrganizationSecurityRuleRepository ruleRepository;

    private Organization defaultOrg;
    private Organization rsf;
    private Organization third;
    private Hospital nabaKoom;
    private Hospital defaultHospital;
    private User adminB;

    @BeforeEach
    void setUp() {
        clearRows();
        defaultOrg = saveOrganization("Default Org");
        rsf = saveOrganization("RSF");
        third = saveOrganization("Third Org");
        nabaKoom = saveHospital("Clinique Naba-Koom", rsf);
        defaultHospital = saveHospital("Hopital Wend-Panga", defaultOrg);
        adminB = userWith("ROLE_HOSPITAL_ADMIN", nabaKoom);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        clearRows();
    }

    @Test
    @DisplayName("QA D845: a hospital administrator lists only their own organisation")
    void hospitalAdminListsOnlyTheirOrganisation() throws Exception {
        mockMvc.perform(get(API + "/organizations").with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(rsf.getId().toString())));
        // hospital-list.ts asks for the active ones: the same scope.
        mockMvc.perform(get(API + "/organizations").param("active", "true").with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("QA D845: a hospital administrator cannot read a foreign organisation, nor its security settings")
    void hospitalAdminCannotReadAForeignOrganisation() throws Exception {
        mockMvc.perform(get(API + "/organizations/{id}", defaultOrg.getId()).with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/organizations/{id}/security/policies", defaultOrg.getId())
                .with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/organizations/{id}/security/rules", defaultOrg.getId())
                .with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/organizations/{id}/security/compliance", defaultOrg.getId())
                .with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isForbidden());
        // Their own organisation stays readable.
        mockMvc.perform(get(API + "/organizations/{id}", rsf.getId()).with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(rsf.getId().toString()));
        mockMvc.perform(get(API + "/organizations/{id}/security/policies", rsf.getId())
                .with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("QA D845: a clinical assignment elsewhere grants no organisation read")
    void aClinicalAssignmentGrantsNoOrganisationRead() throws Exception {
        assign(adminB, "ROLE_DOCTOR", defaultHospital);
        mockMvc.perform(get(API + "/organizations").with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(rsf.getId().toString())));
        mockMvc.perform(get(API + "/organizations/{id}", defaultOrg.getId()).with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an inactive administrator assignment reads nothing")
    void anInactiveAdministratorReadsNothing() throws Exception {
        assignmentRepository.findAll().forEach(a -> {
            a.setActive(false);
            assignmentRepository.save(a);
        });
        mockMvc.perform(get(API + "/organizations").with(hospitalAdmin(adminB, nabaKoom)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a verified super-admin still lists and reads every organisation (the /organizations page)")
    void superAdminReadsEveryOrganisation() throws Exception {
        User superAdmin = userWith("ROLE_SUPER_ADMIN", null);
        mockMvc.perform(get(API + "/organizations").with(superAdmin(superAdmin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get(API + "/organizations/{id}", third.getId()).with(superAdmin(superAdmin)))
            .andExpect(status().isOk());
        mockMvc.perform(get(API + "/organizations/{id}/security/policies", third.getId()).with(superAdmin(superAdmin)))
            .andExpect(status().isOk());
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private RequestPostProcessor hospitalAdmin(User user, Hospital hospital) {
        HospitalContext context = HospitalContext.builder()
            .principalUserId(user.getId())
            .principalUsername(user.getUsername())
            .activeHospitalId(hospital.getId())
            .permittedHospitalIds(Set.of(hospital.getId()))
            .hospitalAdmin(true)
            .build();
        return as(user, "ROLE_HOSPITAL_ADMIN", context);
    }

    private RequestPostProcessor superAdmin(User user) {
        HospitalContext context = HospitalContext.builder()
            .principalUserId(user.getId())
            .principalUsername(user.getUsername())
            .superAdmin(true)
            .build();
        return as(user, "ROLE_SUPER_ADMIN", context);
    }

    private RequestPostProcessor as(User user, String role, HospitalContext context) {
        return request -> {
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
            CustomUserDetails principal = new CustomUserDetails(
                user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(context);
            request.setContextPath(API);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private Organization saveOrganization(String name) {
        return organizationRepository.save(Organization.builder()
            .name(name + " " + nextId())
            .code("ORG" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
    }

    private Hospital saveHospital(String name, Organization organization) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + id)
            .code("H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226555" + id)
            .email("contact" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private User userWith(String roleCode, Hospital hospital) {
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username("user" + suffix)
            .passwordHash("hashed-password")
            .email("user" + suffix + "@example.test")
            .firstName("User")
            .lastName("U" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build());
        assign(user, roleCode, hospital);
        return user;
    }

    private void assign(User user, String roleCode, Hospital hospital) {
        Role role = roleRepository.findByCode(roleCode)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(roleCode.substring("ROLE_".length())).code(roleCode).description(roleCode).build()));
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(roleCode)
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }

    private void clearRows() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        try {
            departmentRepository.deleteAll();
            assignmentRepository.deleteAll();
            userRepository.deleteAll();
            ruleRepository.deleteAll();
            policyRepository.deleteAll();
            hospitalRepository.deleteAll();
            organizationRepository.deleteAll();
        } finally {
            HospitalContextHolder.clear();
        }
    }
}
