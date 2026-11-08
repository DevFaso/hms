package com.example.hms.controller;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
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
import com.example.hms.security.provider.RoleFacilityCompatibility;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.StaffServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA D1536 (round 40): saving any nurse's staff record in /staff answered 500,
 * and by reading the code any radiologist's too: the role checks looked their
 * refusal messages up before checking the fields, and the keys were in no
 * bundle. This creates and then edits a staff record for EVERY role of the live
 * catalog (the roles Liquibase seeds on a real PostgreSQL, the patient and the
 * global-only super-admin aside, and any role a hospital cannot hold), in
 * French, Spanish and English, through the controller, the real service and the
 * real bundles.
 *
 * <p>Each body is what the /staff form sends for the job title
 * (staff-list.ts submitForm): a specialization only for a physician title, a
 * licence only for a licensed title, the employment type and start date always.
 * A doctor or radiologist role on a title the form collects neither for is
 * saved too (#1096 review: the server asked for what the form never sends).
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class StaffSaveEveryRolePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_staff_roles")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String API = "/api";
    private static final String HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final String FR = "fr";
    private static final String EN = "en";

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
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> assignments = new ArrayList<>();
    private final List<UUID> departments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        hospital = hospitalRepository.saveAndFlush(Hospital.builder()
            .name("Hôpital D1536 " + tag)
            .code("D" + tag)
            .city("Ouagadougou").state("Centre").country("Burkina Faso")
            .phoneNumber("+226-555-1536").email(tag.toLowerCase(Locale.ROOT) + "@hospital.test")
            .active(true)
            .build());
    }

    @AfterEach
    void tearDown() {
        try {
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            staffRepository.deleteAll(staffRepository.findAll().stream()
                .filter(s -> s.getHospital() != null && hospital.getId().equals(s.getHospital().getId())).toList());
            departmentRepository.deleteAllById(departments);
            assignmentRepository.deleteAllById(assignments);
            userRepository.deleteAllById(users);
            hospitalRepository.deleteById(hospital.getId());
        } finally {
            ActingScopeTestSupport.clear();
        }
    }

    /** Every role of the live catalog a hospital staff record can carry. */
    private List<String> staffRoles() {
        List<String> roles = new ArrayList<>();
        for (Role role : roleRepository.findAll()) {
            String code = role.getCode();
            if (code == null || code.equals("ROLE_PATIENT") || code.equals("ROLE_SUPER_ADMIN")
                    || !RoleFacilityCompatibility.isCompatible(code, hospital.getFacilityType())) {
                continue;
            }
            roles.add(code);
        }
        return roles;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {FR, "es", EN})
    @DisplayName("every live staff role is created, then edited (department added), with 200")
    void everyRoleCreatesAndEdits(String language) throws Exception {
        List<String> roles = staffRoles();
        assertThat(roles).as("live staff roles")
            .contains("ROLE_NURSE", "ROLE_RADIOLOGIST", "ROLE_DOCTOR", "ROLE_MIDWIFE", "ROLE_LAB_DIRECTOR")
            .hasSizeGreaterThan(15);
        Department department = department("Urgences");

        List<String> failures = new ArrayList<>();
        for (String roleCode : roles) {
            failures.addAll(createThenEdit(roleCode, titleFor(roleCode), department, language));
        }
        assertThat(failures).as("staff saves that did not answer 200 (%s)", language).isEmpty();
    }

    @ParameterizedTest(name = "{0} as {1}")
    @CsvSource({
        "ROLE_DOCTOR,NURSE_PRACTITIONER",
        "ROLE_DOCTOR,ADMINISTRATIVE_STAFF",
        "ROLE_RADIOLOGIST,TECHNICIAN",
        "ROLE_RADIOLOGIST,NURSE"
    })
    @DisplayName("a doctor or radiologist role on a title the form sends no specialization for is saved")
    void aRoleOnAnotherTitleIsSaved(String roleCode, String jobTitle) throws Exception {
        assertThat(createThenEdit(roleCode, jobTitle, department("Radiologie"), FR)).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} {3}")
    @CsvSource(delimiter = '|', value = {
        "fr|ROLE_NURSE|NURSE|employmentType|Les infirmiers doivent avoir un type d'emploi",
        "fr|ROLE_NURSE|NURSE|startDate|Les infirmiers doivent avoir une date de début",
        "es|ROLE_NURSE|NURSE|employmentType|Los enfermeros deben tener un tipo de empleo",
        "es|ROLE_NURSE|NURSE|startDate|Los enfermeros deben tener una fecha de inicio",
        "en|ROLE_NURSE|NURSE|employmentType|Nurses must have an employment type",
        "en|ROLE_NURSE|NURSE|startDate|Nurses must have a start date",
        "fr|ROLE_RADIOLOGIST|RADIOLOGIST|startDate|Les radiologues doivent avoir une date de début",
        "fr|ROLE_RADIOLOGIST|RADIOLOGIST|specialization|Les radiologues doivent avoir une spécialisation",
        "es|ROLE_RADIOLOGIST|RADIOLOGIST|startDate|Los radiólogos deben tener una fecha de inicio",
        "es|ROLE_RADIOLOGIST|RADIOLOGIST|specialization|Los radiólogos deben tener una especialidad",
        "en|ROLE_RADIOLOGIST|RADIOLOGIST|startDate|Radiologists must have a start date",
        "en|ROLE_RADIOLOGIST|RADIOLOGIST|specialization|Radiologists must have a specialization",
        "fr|ROLE_DOCTOR|DOCTOR|specialization|Les médecins doivent avoir une spécialisation",
        "es|ROLE_DOCTOR|DOCTOR|specialization|Los médicos deben tener una especialidad",
        "en|ROLE_DOCTOR|DOCTOR|specialization|Doctors must have a specialization"
    })
    @DisplayName("an edit that really lacks a role value is a 400 naming the field in the reader's language")
    void aMissingRoleValueIsNamed(String language, String roleCode, String jobTitle, String field, String expected)
            throws Exception {
        Map<String, Object> body = formBody(userWithRole(roleCode), roleCode, jobTitle);
        String id = create(body, language);

        body.remove(field);
        JsonNode refusal = putExpecting(id, body, language);

        assertThat(refusal.path("fieldErrors").path(field).asText()).isEqualTo(expected);
        assertThat(refusal.toString()).doesNotContain("staff.nurse.").doesNotContain("staff.radiologist.")
            .doesNotContain("staff.doctor.");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "fr|ROLE_NURSE|NURSE|La date de fin ne peut pas être antérieure à la date de début",
        "es|ROLE_RECEPTIONIST|RECEPTIONIST|La fecha de fin no puede ser anterior a la fecha de inicio",
        "en|ROLE_PHARMACIST|PHARMACIST|The end date cannot be before the start date"
    })
    @DisplayName("an end date before the start date is a 400 on the end date, for every role")
    void anEndBeforeTheStartIsNamed(String language, String roleCode, String jobTitle, String expected) throws Exception {
        Map<String, Object> body = formBody(userWithRole(roleCode), roleCode, jobTitle);
        String id = create(body, language);

        body.put("endDate", LocalDate.now().minusDays(1).toString());
        JsonNode refusal = putExpecting(id, body, language);

        assertThat(refusal.path("fieldErrors").path("endDate").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the request is authoritative: an edit that leaves the start date out clears it, and the end date is judged alone")
    void anEditWithoutAStartDateClearsIt() throws Exception {
        Map<String, Object> body = formBody(userWithRole("ROLE_RECEPTIONIST"), "ROLE_RECEPTIONIST", "RECEPTIONIST");
        String id = create(body, EN);

        // Stored start: today. An end date of yesterday is before it, but the request carries no start.
        body.remove("startDate");
        body.put("endDate", LocalDate.now().minusDays(1).toString());
        MvcResult edited = send(put(API + "/staff/" + id), body, EN);

        String content = edited.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(edited.getResponse().getStatus()).as(content).isEqualTo(200);
        JsonNode saved = objectMapper.readTree(content);
        assertThat(saved.path("startDate").isNull() || saved.path("startDate").isMissingNode()).as(content).isTrue();
        assertThat(saved.path("endDate").asText()).isEqualTo(LocalDate.now().minusDays(1).toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {FR, "es", EN})
    @DisplayName("a nurse or a radiologist cannot clear the start date")
    void aNurseOrRadiologistKeepsAStartDate(String language) throws Exception {
        for (String[] role : new String[][] {{"ROLE_NURSE", "NURSE"}, {"ROLE_RADIOLOGIST", "RADIOLOGIST"}}) {
            Map<String, Object> body = formBody(userWithRole(role[0]), role[0], role[1]);
            String id = create(body, language);
            body.remove("startDate");
            JsonNode refusal = putExpecting(id, body, language);
            assertThat(refusal.path("fieldErrors").has("startDate")).as(refusal.toString()).isTrue();
        }
    }

    @Test
    @DisplayName("a job title in another case is the title; one the form does not offer is a 400 on jobTitle")
    void theJobTitleIsReadOrRefusedOnItsField() throws Exception {
        assertThat(createThenEdit("ROLE_NURSE", "nurse", department("Pédiatrie"), FR)).isEmpty();

        Map<String, Object> body = formBody(userWithRole("ROLE_NURSE"), "ROLE_NURSE", "NURSE");
        body.put("jobTitle", "Brain surgeon");
        MvcResult refused = send(post(API + "/staff"), body, FR);
        String content = refused.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(refused.getResponse().getStatus()).as(content).isEqualTo(400);
        assertThat(objectMapper.readTree(content).path("fieldErrors").path("jobTitle").asText())
            .isEqualTo("Cet intitulé de poste ne fait pas partie des intitulés proposés.");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<String> createThenEdit(String roleCode, String jobTitle, Department department, String language)
            throws Exception {
        List<String> failures = new ArrayList<>();
        Map<String, Object> body = formBody(userWithRole(roleCode), roleCode, jobTitle);
        MvcResult created = send(post(API + "/staff"), body, language);
        if (created.getResponse().getStatus() != 200) {
            failures.add(roleCode + "/" + jobTitle + " create: " + created.getResponse().getStatus() + " "
                + created.getResponse().getContentAsString(StandardCharsets.UTF_8));
            return failures;
        }
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
        // The /staff edit: the same record, the role name sent back, a department typed in.
        body.put("departmentName", department.getName());
        MvcResult edited = send(put(API + "/staff/" + id), body, language);
        if (edited.getResponse().getStatus() != 200) {
            failures.add(roleCode + "/" + jobTitle + " edit: " + edited.getResponse().getStatus() + " "
                + edited.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return failures;
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Map<String, Object> body, String language)
            throws Exception {
        return mockMvc.perform(request.contextPath(API)
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    }

    private String create(Map<String, Object> body, String language) throws Exception {
        MvcResult created = send(post(API + "/staff"), body, language);
        assertThat(created.getResponse().getStatus())
            .as(created.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
    }

    private JsonNode putExpecting(String id, Map<String, Object> body, String language) throws Exception {
        MvcResult result = send(put(API + "/staff/" + id), body, language);
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(400);
        return objectMapper.readTree(content);
    }

    /** The job title the /staff form would pick for a role: its own name when it is one, else administrative staff. */
    private static String titleFor(String roleCode) {
        String bare = roleCode.substring("ROLE_".length());
        return Arrays.stream(JobTitle.values()).anyMatch(t -> t.name().equals(bare))
            ? bare : JobTitle.ADMINISTRATIVE_STAFF.name();
    }

    /** What staff-list.ts submitForm sends for this job title. */
    private Map<String, Object> formBody(User user, String roleCode, String jobTitle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userEmail", user.getEmail());
        body.put("hospitalName", hospital.getName());
        body.put("jobTitle", jobTitle);
        body.put("roleName", roleCode);
        body.put("employmentType", "FULL_TIME");
        body.put("startDate", LocalDate.now().toString());
        if (StaffServiceImpl.SPECIALTY_JOB_TITLES.contains(jobTitle.toUpperCase(Locale.ROOT))) {
            body.put("specialization", "RADIOLOGY");
        }
        if (StaffServiceImpl.CLINICAL_JOB_TITLES.contains(jobTitle.toUpperCase(Locale.ROOT))) {
            body.put("licenseNumber", "LIC-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return body;
    }

    private Department department(String name) {
        User owner = userWithRole(HOSPITAL_ADMIN);
        UserRoleHospitalAssignment assignment = assignmentRepository
            .findFirstByUserIdAndHospitalIdAndRoleId(owner.getId(), hospital.getId(), role(HOSPITAL_ADMIN).getId())
            .orElseThrow();
        Department saved = departmentRepository.saveAndFlush(Department.builder()
            .name(name)
            .code("D" + sequence.incrementAndGet() + UUID.randomUUID().toString().substring(0, 4))
            .email("dept" + UUID.randomUUID().toString().substring(0, 8) + "@dept.test")
            .phoneNumber("+226-111-1536")
            .active(true)
            .hospital(hospital)
            .assignment(assignment)
            .build());
        departments.add(saved.getId());
        return saved;
    }

    private User userWithRole(String roleCode) {
        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = userRepository.saveAndFlush(User.builder()
            .username("staff-" + identifier)
            .passwordHash("password")
            .email(identifier + "@staff.test")
            .firstName("Alimata")
            .lastName("Nikiéma " + identifier)
            .phoneNumber("+2267" + String.format("%07d", sequence.incrementAndGet() * 7919 % 10_000_000))
            .build());
        users.add(user.getId());
        UserRoleHospitalAssignment assignment = assignmentRepository.saveAndFlush(UserRoleHospitalAssignment.builder()
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
        assignments.add(assignment.getId());
        return user;
    }

    /** A role of the live catalog: never created here. */
    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseThrow(() -> new IllegalStateException(code + " is not in the seeded role catalog"));
    }
}
