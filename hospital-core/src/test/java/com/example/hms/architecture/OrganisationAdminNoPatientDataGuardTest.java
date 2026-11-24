package com.example.hms.architecture;

import com.example.hms.config.SecurityConstants;
import com.example.hms.service.PatientSubjectReaderRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform admin rules 2026-11-20, rule 1b: the organisation's administrator
 * ({@code ROLE_ADMIN}) reads no patient or clinical data, anywhere. It
 * registers staff, works their assignments and reads totals.
 *
 * <p>Until this rule {@code ROLE_ADMIN} was a general back-office role and was
 * named on the patient chart, the front desk, the audit trail, imaging, the
 * mortality register and more. Each of those lists was changed by hand, and
 * the next one will be written by someone who has not read the rule. So: every
 * handler of every controller whose {@code @PreAuthorize} admits the role is
 * RECORDED below with the reason it holds no patient or clinical data. A new
 * handler that names the role fails here until it is recorded or the role is
 * removed.
 */
class OrganisationAdminNoPatientDataGuardTest {

    private static final String ROLES = "UserRoleHospitalAssignmentController#";
    private static final String STAFF_ROW = "one staff assignment of the caller's organisation "
        + "(AssignmentScope: staff rows only, never a PATIENT row)";
    private static final String CATALOGUE = "the education content catalogue: a document about no patient";

    /** {@code Controller#handler} → why it shows the organisation admin no patient or clinical data. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("OrganisationAdminController#hospitals", "the hospitals of its own organisation: names and codes"),
        entry("OrganisationAdminController#summary", "totals per hospital; a small sensitive count is withheld"),
        entry("RoleController#list", "role names for the register form"),
        entry("UserController#adminRegister", "staff registration; UserAccountAccess refuses PATIENT for this role"),
        entry("UserController#getAllUsers", "the staff directory: the staff-only query, no e-mail or username"),
        entry("UserController#searchUsers", "the staff directory search: the same staff-only query"),
        entry(ROLES + "createAssignment", STAFF_ROW),
        entry(ROLES + "getAssignment", STAFF_ROW),
        entry(ROLES + "getAllAssignments", "the staff assignments of its organisation; PATIENT rows are filtered out"),
        entry(ROLES + "termSearchAssignments", "the same list narrowed to a typed term; PATIENT rows are filtered out"),
        entry(ROLES + "regenerateAssignmentCode", STAFF_ROW),
        entry(ROLES + "resendNotification", STAFF_ROW),
        entry(ROLES + "revealActivationCode", STAFF_ROW),
        entry(ROLES + "confirmAssignment", STAFF_ROW),
        entry(ROLES + "deactivateAssignment", STAFF_ROW),
        entry("PatientEducationController#createResource", CATALOGUE),
        entry("PatientEducationController#updateResource", CATALOGUE),
        entry("PatientEducationController#deleteResource", CATALOGUE),
        entry("PatientEducationController#getResourceById", CATALOGUE),
        entry("PatientEducationController#getAllResources", CATALOGUE),
        entry("PatientEducationController#searchResources", CATALOGUE),
        entry("PatientEducationController#getResourcesByCategory", CATALOGUE),
        entry("PatientEducationController#getResourcesByType", CATALOGUE),
        entry("PatientEducationController#getResourcesByLanguage", CATALOGUE),
        entry("PatientEducationController#getPopularResourcesByCategory", CATALOGUE),
        entry("PatientEducationController#getAverageRating", "a resource's average rating: one number, no patient"));

    /** {@code 'ROLE_ADMIN'} as an authority or {@code 'ADMIN'} as a role; never HOSPITAL_ADMIN, SUPER_ADMIN, PROVIDER_ADMIN. */
    private static final Pattern ADMITS = Pattern.compile("'(ROLE_)?ADMIN'");

    static boolean admitsOrganisationAdmin(String expression) {
        return expression != null && ADMITS.matcher(expression).find();
    }

    /** Every handler that admits the role: {@code Controller#handler} → its expression. */
    private static Map<String, String> admitted() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        Map<String, String> found = new TreeMap<>();
        int controllers = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            controllers++;
            PreAuthorize onClass = type.getAnnotation(PreAuthorize.class);
            for (Method method : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                // A method's own annotation replaces its class's.
                PreAuthorize own = method.getAnnotation(PreAuthorize.class);
                PreAuthorize effective = own != null ? own : onClass;
                String expression = effective != null ? effective.value() : null;
                if (admitsOrganisationAdmin(expression)) {
                    found.put(type.getSimpleName() + "#" + method.getName(), expression);
                }
            }
        }
        assertThat(controllers).as("every controller must be read").isGreaterThan(150);
        return found;
    }

    @Test
    @DisplayName("every handler that admits ROLE_ADMIN is recorded as holding no patient or clinical data")
    void everyHandlerAdmittingTheOrganisationAdminIsRecorded() throws ClassNotFoundException {
        Map<String, String> admitted = admitted();

        Map<String, String> unrecorded = new TreeMap<>(admitted);
        unrecorded.keySet().removeAll(RECORDED.keySet());
        assertThat(unrecorded)
            .as("a handler admits ROLE_ADMIN, the organisation's administrator, who reads no patient or clinical"
                + " data (platform admin rules, rule 1b): remove the role from its list (a hospital's own"
                + " administrator is ROLE_HOSPITAL_ADMIN), or record here why the handler shows no patient")
            .isEmpty();
        assertThat(admitted.keySet())
            .as("recorded handlers that no longer admit the role, or no longer exist: remove them")
            .containsAll(RECORDED.keySet());
    }

    @Test
    @DisplayName("the URL layer names ROLE_ADMIN on the role list only (the other two matchers use their constants)")
    void theUrlLayerNamesTheRoleOnce() throws IOException {
        Path source = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");
        List<String> naming = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
            .map(String::strip)
            .filter(line -> !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("import "))
            .filter(line -> Pattern.compile("(?<![A-Z_])ROLE_ADMIN\\b").matcher(line).find())
            .toList();
        assertThat(naming)
            .as("a URL matcher admits ROLE_ADMIN: only GET /roles may (and /assignments/**, admin-register"
                + " through SecurityConstants). A patient or clinical path never does")
            .containsExactly(".hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_ADMIN)");
        // The constants that carry the role: the staff directory, staff registration, the assignments.
        assertThat(SecurityConstants.USER_REGISTRAR_AUTHORITIES)
            .as("every role on this list registers PATIENT accounts").doesNotContain("'ROLE_ADMIN'");
    }

    @Test
    @DisplayName("no reader list of a patient's records names ROLE_ADMIN")
    void noPatientSubjectReaderListNamesTheRole() throws IllegalAccessException {
        Set<String> naming = new TreeSet<>();
        int lists = 0;
        for (Field field : PatientSubjectReaderRoles.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Collection.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            lists++;
            if (((Collection<?>) field.get(null)).contains(SecurityConstants.ROLE_ADMIN)) {
                naming.add(field.getName());
            }
        }
        assertThat(lists).as("the reader lists must be read").isGreaterThan(3);
        assertThat(naming).isEmpty();
    }

    @Test
    @DisplayName("the expression reader tells the organisation admin from the other admin roles")
    void theReaderIsNotFooledByTheOtherAdminRoles() {
        assertThat(admitsOrganisationAdmin("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN','PROVIDER_ADMIN')")).isFalse();
        assertThat(admitsOrganisationAdmin("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")).isFalse();
        assertThat(admitsOrganisationAdmin("isAuthenticated()")).isFalse();
        assertThat(admitsOrganisationAdmin(null)).isFalse();
        assertThat(admitsOrganisationAdmin("hasAnyRole('DOCTOR', 'ADMIN')")).isTrue();
        assertThat(admitsOrganisationAdmin("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_ADMIN')")).isTrue();
        assertThat(admitsOrganisationAdmin(
            "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'ADMIN','DOCTOR')")).isTrue();
    }
}
