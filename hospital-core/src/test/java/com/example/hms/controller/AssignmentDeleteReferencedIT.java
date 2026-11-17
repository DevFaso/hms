package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.support.AssignmentReferences;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA round 14: deleting a role assignment that a department still named went
 * through the staff and encounter checks, reached the database's
 * {@code fk_department_assignment} and came back as a 500. Same endpoint, same
 * call as the report: {@code DELETE /api/assignments/{id}}.
 */
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class AssignmentDeleteReferencedIT extends BaseIT {

    private static final String API_CONTEXT = "/api";

    @Autowired private MockMvc mockMvc;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AssignmentReferences assignmentReferences;

    @BeforeEach
    void setUp() {
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        deleteRows();
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
        departmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        hospitalRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void anAssignmentADepartmentStillNamesIsRefusedWithA409InTheCallersLanguage() throws Exception {
        Hospital hospital = createHospital();
        UserRoleHospitalAssignment assignment = createAssignment(hospital);
        createDepartment(hospital, assignment);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("encore utilisée par des services")));

        assertThat(assignmentRepository.findById(assignment.getId())).isPresent();
    }

    @Test
    void theRefusalNamesWhatStillUsesItInSpanishToo() throws Exception {
        Hospital hospital = createHospital();
        UserRoleHospitalAssignment assignment = createAssignment(hospital);
        createDepartment(hospital, assignment);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "es"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("todavía la usan servicios")));
    }

    /**
     * QA D904: the 409 came back as {@code field="Impossible de supprimer
     * l'affectation"} and a message starting "elle est encore utilisée…": the
     * handler took the text before the first colon as a field name, and the
     * toast showed only the lower-case second half. The whole sentence, in
     * all three languages, and no field.
     */
    @Test
    void theRefusalIsSentWholeInEveryLanguageNotCutAtItsColon() throws Exception {
        Hospital hospital = createHospital();
        UserRoleHospitalAssignment assignment = createAssignment(hospital);
        createDepartment(hospital, assignment);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message",
                startsWith("Impossible de supprimer l'affectation : elle est encore utilisée par des services")))
            .andExpect(jsonPath("$.field").doesNotExist());
        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "es"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("No se puede eliminar la asignación: todavía la usan")))
            .andExpect(jsonPath("$.field").doesNotExist());
        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "en"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Cannot delete assignment: it is still used by departments")))
            .andExpect(jsonPath("$.field").doesNotExist());
    }

    @Test
    void anAssignmentNothingUsesIsStillDeleted() throws Exception {
        Hospital hospital = createHospital();
        UserRoleHospitalAssignment assignment = createAssignment(hospital);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", assignment.getId()).contextPath(API_CONTEXT))
            .andExpect(status().isNoContent());

        assertThat(assignmentRepository.findById(assignment.getId())).isEmpty();
    }

    @Test
    void anotherAssignmentAtTheSameHospitalDoesNotBlockTheDelete() throws Exception {
        Hospital hospital = createHospital();
        UserRoleHospitalAssignment headOfDepartment = createAssignment(hospital);
        createDepartment(hospital, headOfDepartment);
        UserRoleHospitalAssignment unused = createAssignment(hospital);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", unused.getId()).contextPath(API_CONTEXT))
            .andExpect(status().isNoContent());
    }

    /**
     * The guard for every other key into the assignment table: each mapped
     * entity that points at an assignment is found by the pre-check and has a
     * label in every locale, so a new one is named in words, not as a key.
     */
    @Test
    void everyEntityThatPointsAtAnAssignmentIsCheckedAndLabelled() throws Exception {
        List<EntityType<?>> types = assignmentReferences.referencingTypes();
        List<String> names = types.stream().map(type -> type.getJavaType().getSimpleName()).toList();
        assertThat(names).contains("Department", "DepartmentTranslation", "Staff", "Encounter", "Permission",
            "Prescription", "LabOrder", "ServiceTranslation", "DischargeApproval", "PatientVitalSign")
            .doesNotContain("AuditEventLog", "UserRoleHospitalAssignment");

        List<String> keys = new ArrayList<>(names);
        keys.add("other");
        List<String> missing = new ArrayList<>();
        for (String bundle : List.of("messages-admin", "messages-admin_en", "messages-admin_fr", "messages-admin_es")) {
            Properties properties = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/" + bundle + ".properties");
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String name : keys) {
                String key = "users.assignment.reference." + name;
                if (properties.getProperty(key, "").isBlank()) {
                    missing.add(bundle + ": " + key);
                }
            }
        }
        assertThat(missing).as("labels for what still uses an assignment").isEmpty();
    }

    private Hospital createHospital() {
        String code = nextIdentifier();
        Organization organization = organizationRepository.saveAndFlush(Organization.builder()
            .name("Org " + code).code("O" + code).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        Hospital hospital = Hospital.builder()
            .name("Hospital " + code).code("H" + code).city("Ouagadougou").state("Centre")
            .country("Burkina Faso").phoneNumber("+226-555-0000").email(code.toLowerCase() + "@hospital.test")
            .active(true).build();
        hospital.setOrganization(organization);
        return hospitalRepository.saveAndFlush(hospital);
    }

    private void createDepartment(Hospital hospital, UserRoleHospitalAssignment assignment) {
        String code = nextIdentifier();
        departmentRepository.saveAndFlush(Department.builder()
            .name("Cardiology " + code).code("D" + code).email(code.toLowerCase() + "@dept.test")
            .phoneNumber("+226-111-0000").active(true).hospital(hospital).assignment(assignment).build());
    }

    private UserRoleHospitalAssignment createAssignment(Hospital hospital) {
        Role role = roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")
            .orElseGet(() -> roleRepository.save(Role.builder().name("HOSPITAL_ADMIN").code("ROLE_HOSPITAL_ADMIN").build()));
        String identifier = nextIdentifier();
        User user = userRepository.saveAndFlush(User.builder()
            .username("user-" + identifier).passwordHash("password").email(identifier + "@example.com")
            .firstName("User").lastName(identifier)
            .phoneNumber("+226" + Math.abs(identifier.hashCode() % 100_000_000)).build());
        return assignmentRepository.saveAndFlush(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + identifier).description("Test assignment").startDate(LocalDate.now())
            .registeredBy(user).user(user).hospital(hospital).role(role).assignedAt(LocalDateTime.now())
            .active(true).build());
    }

    private static String nextIdentifier() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
