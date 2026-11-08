package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.JobTitle;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA D1536 (round 40): saving any nurse's staff record in /staff answered 500,
 * and by reading the code any radiologist's too: the role-specific checks
 * looked their refusal messages up before checking the fields, and the keys
 * were in no bundle. This creates and then edits a staff record for EVERY
 * seeded staff role (RoleSeeder's catalog, the patient role aside), in French,
 * Spanish and English, through the controller, the real service and the real
 * message bundles; and checks that a nurse, radiologist or doctor record that
 * really lacks a value is refused 400 with each field named in the reader's
 * language, never 500 and never the raw key.
 */
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class StaffSaveEveryRoleIT extends BaseIT {

    private static final String API = "/api";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private Hospital hospital;

    @BeforeEach
    void setUp() {
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        deleteRows();
        hospital = hospitalRepository.saveAndFlush(Hospital.builder()
            .name("Hôpital D1536 " + UUID.randomUUID().toString().substring(0, 6))
            .code("D" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase())
            .city("Ouagadougou").state("Centre").country("Burkina Faso")
            .phoneNumber("+226-555-1536").email("d1536@hospital.test")
            .active(true)
            .build());
    }

    @AfterEach
    void tearDown() {
        try {
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            deleteRows();
        } finally {
            ActingScopeTestSupport.clear();
        }
    }

    private void deleteRows() {
        staffRepository.deleteAll();
        departmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        hospitalRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Every role a staff record can carry: RoleSeeder's catalog and every role code a migration
     * writes, without the patient role and the super-admin (global only: it can hold no hospital
     * assignment, so no staff record).
     */
    @SuppressWarnings("unchecked")
    private static List<String> staffRoles() throws java.io.IOException {
        Map<String, String> catalog = (Map<String, String>) ReflectionTestUtils.invokeMethod(
            com.example.hms.seed.RoleSeeder.class, "buildRoleCatalog");
        java.util.Set<String> roles = new java.util.TreeSet<>(catalog.keySet());
        java.util.regex.Pattern code = java.util.regex.Pattern.compile("'(ROLE_[A-Z_]+)'");
        try (java.util.stream.Stream<java.nio.file.Path> files =
                 java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/db/migration"))) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                java.util.regex.Matcher m = code.matcher(java.nio.file.Files.readString(file));
                while (m.find()) {
                    roles.add(m.group(1));
                }
            }
        }
        roles.remove("ROLE_PATIENT");
        roles.remove("ROLE_SUPER_ADMIN");
        return new ArrayList<>(roles);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fr", "es", "en"})
    @DisplayName("every staff role is created, then edited (department added), with 200")
    void everyRoleCreatesAndEdits(String language) throws Exception {
        // A role the facility cannot hold (a provider-only role at a hospital) has no staff record
        // there: the assignment itself is refused, before any staff save.
        List<String> roles = new ArrayList<>();
        List<String> notHeldHere = new ArrayList<>();
        for (String role : staffRoles()) {
            (com.example.hms.security.provider.RoleFacilityCompatibility.isCompatible(role, hospital.getFacilityType())
                ? roles : notHeldHere).add(role);
        }
        assertThat(roles).as("staff roles held at a hospital")
            .contains("ROLE_NURSE", "ROLE_RADIOLOGIST", "ROLE_DOCTOR", "ROLE_MIDWIFE", "ROLE_LAB_DIRECTOR")
            .hasSizeGreaterThan(25);
        assertThat(notHeldHere).as("roles a hospital cannot hold").containsOnly("ROLE_PROVIDER_ADMIN");
        Department department = department("Urgences");

        List<String> failures = new ArrayList<>();
        for (String roleCode : roles) {
            User user = userWithRole(roleCode);
            Map<String, Object> body = validBody(user, roleCode);

            MvcResult created = mockMvc.perform(post(API + "/staff").contextPath(API)
                    .header("Accept-Language", language)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andReturn();
            if (created.getResponse().getStatus() != 200) {
                failures.add(roleCode + " create: " + created.getResponse().getStatus() + " "
                    + created.getResponse().getContentAsString());
                continue;
            }
            String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

            // The /staff edit: the same record, the role name sent back, a department typed in.
            body.put("departmentName", department.getName());
            MvcResult edited = mockMvc.perform(put(API + "/staff/" + id).contextPath(API)
                    .header("Accept-Language", language)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andReturn();
            if (edited.getResponse().getStatus() != 200) {
                failures.add(roleCode + " edit: " + edited.getResponse().getStatus() + " "
                    + edited.getResponse().getContentAsString());
            }
        }
        assertThat(failures).as("staff saves that did not answer 200 (%s)", language).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "fr|ROLE_NURSE|employmentType|Les infirmiers doivent avoir un type d'emploi",
        "fr|ROLE_NURSE|startDate|Les infirmiers doivent avoir une date de début",
        "es|ROLE_NURSE|employmentType|Los enfermeros deben tener un tipo de empleo",
        "es|ROLE_NURSE|startDate|Los enfermeros deben tener una fecha de inicio",
        "en|ROLE_NURSE|employmentType|Nurses must have an employment type",
        "en|ROLE_NURSE|startDate|Nurses must have a start date",
        "fr|ROLE_RADIOLOGIST|startDate|Les radiologues doivent avoir une date de début",
        "fr|ROLE_RADIOLOGIST|specialization|Les radiologues doivent avoir une spécialisation",
        "es|ROLE_RADIOLOGIST|startDate|Los radiólogos deben tener una fecha de inicio",
        "es|ROLE_RADIOLOGIST|specialization|Los radiólogos deben tener una especialidad",
        "en|ROLE_RADIOLOGIST|startDate|Radiologists must have a start date",
        "en|ROLE_RADIOLOGIST|specialization|Radiologists must have a specialization",
        "fr|ROLE_DOCTOR|specialization|Les médecins doivent avoir une spécialisation",
        "es|ROLE_DOCTOR|specialization|Los médicos deben tener una especialidad",
        "en|ROLE_DOCTOR|specialization|Doctors must have a specialization"
    })
    @DisplayName("an edit that really lacks a role value is a 400 naming the field in the reader's language")
    void aMissingRoleValueIsNamed(String language, String roleCode, String field, String expected) throws Exception {
        User user = userWithRole(roleCode);
        Map<String, Object> body = validBody(user, roleCode);
        String id = create(body, language);

        body.remove(field);
        JsonNode refusal = putExpecting(id, body, language, 400);

        assertThat(refusal.path("fieldErrors").path(field).asText()).isEqualTo(expected);
        assertThat(refusal.toString()).doesNotContain("staff." + roleKeyPart(roleCode) + ".");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "fr|ROLE_NURSE|La date de fin ne peut pas être antérieure à la date de début",
        "es|ROLE_RADIOLOGIST|La fecha de fin no puede ser anterior a la fecha de inicio",
        "en|ROLE_NURSE|The end date cannot be before the start date"
    })
    @DisplayName("an end date before the start date is a 400 on the end date")
    void anEndBeforeTheStartIsNamed(String language, String roleCode, String expected) throws Exception {
        User user = userWithRole(roleCode);
        Map<String, Object> body = validBody(user, roleCode);
        String id = create(body, language);

        body.put("endDate", LocalDate.now().minusDays(1).toString());
        JsonNode refusal = putExpecting(id, body, language, 400);

        assertThat(refusal.path("fieldErrors").path("endDate").asText()).isEqualTo(expected);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String create(Map<String, Object> body, String language) throws Exception {
        MvcResult created = mockMvc.perform(post(API + "/staff").contextPath(API)
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();
        assertThat(created.getResponse().getStatus())
            .as(created.getResponse().getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
    }

    private JsonNode putExpecting(String id, Map<String, Object> body, String language, int status) throws Exception {
        MvcResult result = mockMvc.perform(put(API + "/staff/" + id).contextPath(API)
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(status);
        return objectMapper.readTree(content);
    }

    private static String roleKeyPart(String roleCode) {
        return roleCode.substring("ROLE_".length()).toLowerCase(java.util.Locale.ROOT);
    }

    /** A complete body for the role, as the /staff form sends it (the role name included). */
    private Map<String, Object> validBody(User user, String roleCode) {
        String bare = roleCode.substring("ROLE_".length());
        String jobTitle = java.util.Arrays.stream(JobTitle.values()).anyMatch(t -> t.name().equals(bare))
            ? bare : JobTitle.ADMINISTRATIVE_STAFF.name();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userEmail", user.getEmail());
        body.put("hospitalName", hospital.getName());
        body.put("jobTitle", jobTitle);
        body.put("roleName", roleCode);
        body.put("employmentType", "FULL_TIME");
        body.put("specialization", "RADIOLOGY");
        body.put("licenseNumber", "LIC-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("startDate", LocalDate.now().toString());
        return body;
    }

    private Department department(String name) {
        User owner = userWithRole("ROLE_HOSPITAL_ADMIN");
        UserRoleHospitalAssignment assignment = assignmentRepository
            .findFirstByUserIdAndHospitalIdAndRoleId(owner.getId(), hospital.getId(), role("ROLE_HOSPITAL_ADMIN").getId())
            .orElseThrow();
        return departmentRepository.saveAndFlush(Department.builder()
            .name(name)
            .code("URG" + sequence.incrementAndGet())
            .email("urgences" + sequence.get() + "@dept.test")
            .phoneNumber("+226-111-1536")
            .active(true)
            .hospital(hospital)
            .assignment(assignment)
            .build());
    }

    private User userWithRole(String roleCode) {
        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = userRepository.saveAndFlush(User.builder()
            .username("staff-" + identifier)
            .passwordHash("password")
            .email(identifier + "@staff.test")
            .firstName("Alimata")
            .lastName("Nikiéma " + identifier)
            .phoneNumber("+2267" + String.format("%07d", sequence.incrementAndGet()))
            .build());
        assignmentRepository.saveAndFlush(UserRoleHospitalAssignment.builder()
            .assignmentCode("D1536-" + identifier)
            .description("D1536 staff role")
            .startDate(LocalDate.now())
            .registeredBy(user)
            .user(user)
            .hospital(hospital)
            .role(role(roleCode))
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return user;
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.saveAndFlush(Role.builder()
                .name(code.substring("ROLE_".length()))
                .code(code)
                .build()));
    }
}
