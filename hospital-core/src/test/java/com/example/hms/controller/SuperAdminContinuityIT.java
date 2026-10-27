package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D902 (Critical): on dev the super-admin opened /admin-assignments, hit
 * the trash icon on the first row - their own "Super administrateur - GLOBAL"
 * assignment, the platform's only one - and {@code DELETE /api/assignments/{id}}
 * answered 204. The next sign-in had no super-admin role, and nobody was left
 * to grant it back. These replay that call, then every other path that takes
 * an assignment or the account away, as the signed-in account.
 */
@AutoConfigureMockMvc(addFilters = false)
class SuperAdminContinuityIT extends BaseIT {

    private static final String API_CONTEXT = "/api";

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    @BeforeEach
    void setUp() {
        deleteRows();
    }

    @AfterEach
    void tearDown() {
        try {
            deleteRows();
        } finally {
            SecurityContextHolder.clearContext();
            ActingScopeTestSupport.clear();
        }
    }

    private void deleteRows() {
        ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
        auditEventLogRepository.deleteAll();
        assignmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void theExactReportedCallIsRefusedAndTheSuperAdminKeepsTheRole() throws Exception {
        User admin = createUser();
        UserRoleHospitalAssignment own = createSuperAdminAssignment(admin);
        signInAs(admin);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", own.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Vous ne pouvez pas supprimer, désactiver")))
            .andExpect(jsonPath("$.field").doesNotExist());

        assertThat(assignmentRepository.findById(own.getId())).get()
            .extracting(UserRoleHospitalAssignment::getActive).isEqualTo(true);
    }

    @Test
    void theRefusalIsTranslatedInSpanishAndEnglish() throws Exception {
        User admin = createUser();
        UserRoleHospitalAssignment own = createSuperAdminAssignment(admin);
        // A second super-admin exists: the own-row rule refuses on its own.
        createSuperAdminAssignment(createUser());
        signInAs(admin);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", own.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "es"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("No puede eliminar, desactivar")));
        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", own.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "en"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("You cannot remove, deactivate")));
        assertThat(assignmentRepository.findById(own.getId())).isPresent();
    }

    @Test
    void deactivatingOrRetiringYourOwnAssignmentsIsRefusedToo() throws Exception {
        User admin = createUser();
        UserRoleHospitalAssignment own = createSuperAdminAssignment(admin);
        createSuperAdminAssignment(createUser());
        signInAs(admin);

        mockMvc.perform(patch(API_CONTEXT + "/assignments/{id}/deactivate", own.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Vous ne pouvez pas supprimer, désactiver")));
        mockMvc.perform(delete(API_CONTEXT + "/assignments/user/{userId}", admin.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict());

        assertThat(assignmentRepository.findById(own.getId())).get()
            .extracting(UserRoleHospitalAssignment::getActive).isEqualTo(true);
    }

    @Test
    void deletingYourOwnAccountIsRefused() throws Exception {
        User admin = createUser();
        createSuperAdminAssignment(admin);
        createSuperAdminAssignment(createUser());
        signInAs(admin);

        mockMvc.perform(delete(API_CONTEXT + "/users/{id}", admin.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Vous ne pouvez pas supprimer votre propre compte")));

        assertThat(userRepository.findById(admin.getId())).get().extracting(User::isDeleted).isEqualTo(false);
    }

    @Test
    void theLastLiveSuperAdminAssignmentCannotBeTakenAwayByAnyone() throws Exception {
        // The caller presents the role but holds no live assignment of it
        // (a token minted before theirs was switched off): another account's
        // row is the platform's last.
        User caller = createUser();
        User last = createUser();
        UserRoleHospitalAssignment lastRow = createSuperAdminAssignment(last);
        signInAs(caller);

        mockMvc.perform(delete(API_CONTEXT + "/assignments/{id}", lastRow.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "fr"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Cette modification laisserait la plateforme")));
        mockMvc.perform(patch(API_CONTEXT + "/assignments/{id}/deactivate", lastRow.getId())
                .contextPath(API_CONTEXT).header("Accept-Language", "es"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("Este cambio dejaría la plataforma")));
        mockMvc.perform(delete(API_CONTEXT + "/assignments/user/{userId}", last.getId()).contextPath(API_CONTEXT))
            .andExpect(status().isConflict());
        mockMvc.perform(delete(API_CONTEXT + "/users/{id}", last.getId()).contextPath(API_CONTEXT)
                .header("Accept-Language", "en"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", startsWith("This change would leave the platform")));
        mockMvc.perform(put(API_CONTEXT + "/users/{id}", last.getId()).contextPath(API_CONTEXT)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isConflict());

        assertThat(assignmentRepository.findById(lastRow.getId())).get()
            .extracting(UserRoleHospitalAssignment::getActive).isEqualTo(true);
        User reloaded = userRepository.findById(last.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.isDeleted()).isFalse();
    }

    @Test
    void anotherSuperAdminsRowCanStillBeRemovedWhileOneRemains() throws Exception {
        User admin = createUser();
        createSuperAdminAssignment(admin);
        UserRoleHospitalAssignment other = createSuperAdminAssignment(createUser());
        signInAs(admin);

        mockMvc.perform(patch(API_CONTEXT + "/assignments/{id}/deactivate", other.getId()).contextPath(API_CONTEXT))
            .andExpect(status().isNoContent());

        assertThat(assignmentRepository.findById(other.getId())).get()
            .extracting(UserRoleHospitalAssignment::getActive).isEqualTo(false);
    }

    private void signInAs(User user) {
        ActingScopeTestSupport.globalSuperAdmin(user.getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    private UserRoleHospitalAssignment createSuperAdminAssignment(User user) {
        Role role = roleRepository.findByCode("ROLE_SUPER_ADMIN")
            .orElseGet(() -> roleRepository.save(Role.builder().name("SUPER_ADMIN").code("ROLE_SUPER_ADMIN").build()));
        String identifier = nextIdentifier();
        return assignmentRepository.saveAndFlush(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + identifier).description("Super administrator - global")
            .startDate(LocalDate.now()).registeredBy(user).user(user).hospital(null).role(role)
            .assignedAt(LocalDateTime.now()).active(true).build());
    }

    private User createUser() {
        String identifier = nextIdentifier();
        return userRepository.saveAndFlush(User.builder()
            .username("user-" + identifier).passwordHash("password").email(identifier + "@example.com")
            .firstName("User").lastName(identifier).isActive(true)
            .phoneNumber("+226" + Math.abs(identifier.hashCode() % 100_000_000)).build());
    }

    private static String nextIdentifier() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
