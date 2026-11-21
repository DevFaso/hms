package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform admin rules 2026-11-20, rule 1a. The URL layer admits the
 * organisation admin ({@code ROLE_ADMIN}) on {@code /assignments/**}, so the
 * handler annotations are what decides which of them it really reaches: one
 * assignment at a time, its details and codes, and the switch-off. Never the
 * edit, the hard deletes, the multi-hospital grant or the import.
 *
 * <p>A handler added here with no annotation would be open to that role by
 * the URL layer alone; a handler widened to it is a decision. Both fail here.
 */
class AssignmentEndpointRolesTest {

    /** The handlers an organisation admin reaches. Adding one is a decision: record it here. */
    private static final Set<String> ORGANISATION_ADMIN_HANDLERS = Set.of(
        "createAssignment",
        "getAssignment",
        "getAllAssignments",
        "regenerateAssignmentCode",
        "resendNotification",
        "revealActivationCode",
        "confirmAssignment",
        "deactivateAssignment");

    private static boolean isHandler(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    private static boolean isPublicRoute(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping != null && Arrays.stream(mapping.path()).anyMatch(path -> path.startsWith("/public/"));
    }

    @Test
    @DisplayName("every /assignments handler states its own roles, the public code routes aside")
    void everyHandlerStatesItsRoles() {
        Set<String> unguarded = new TreeSet<>();
        int handlers = 0;
        for (Method method : UserRoleHospitalAssignmentController.class.getDeclaredMethods()) {
            if (!isHandler(method)) {
                continue;
            }
            handlers++;
            if (method.getAnnotation(PreAuthorize.class) == null && !isPublicRoute(method)) {
                unguarded.add(method.getName());
            }
        }
        assertThat(handlers).as("the controller's handlers were read").isGreaterThan(10);
        assertThat(unguarded)
            .as("the URL layer admits ROLE_ADMIN on /assignments/**: a handler with no @PreAuthorize is open to it")
            .isEmpty();
    }

    @Test
    @DisplayName("the organisation admin reaches exactly the recorded handlers")
    void organisationAdminReachesTheRecordedHandlersOnly() {
        Set<String> admitted = new TreeSet<>();
        for (Method method : UserRoleHospitalAssignmentController.class.getDeclaredMethods()) {
            PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
            if (isHandler(method) && guard != null && admitsOrganisationAdmin(guard.value())) {
                admitted.add(method.getName());
            }
        }
        assertThat(admitted).containsExactlyInAnyOrderElementsOf(ORGANISATION_ADMIN_HANDLERS);
    }

    /** {@code 'ROLE_ADMIN'} as an authority, or {@code 'ADMIN'} as a role; never {@code HOSPITAL_ADMIN} or {@code SUPER_ADMIN}. */
    private static boolean admitsOrganisationAdmin(String expression) {
        return expression.contains("'ROLE_ADMIN'") || expression.contains("'ADMIN'");
    }

    @Test
    @DisplayName("the reading of an expression tells the organisation admin from the other admins")
    void theExpressionReaderIsNotFooledByTheOtherAdminRoles() {
        assertThat(admitsOrganisationAdmin("hasAnyRole('HOSPITAL_ADMIN','SUPER_ADMIN')")).isFalse();
        assertThat(admitsOrganisationAdmin("hasRole('SUPER_ADMIN')")).isFalse();
        assertThat(admitsOrganisationAdmin("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")).isFalse();
        assertThat(admitsOrganisationAdmin("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_ADMIN')"))
            .isTrue();
        assertThat(admitsOrganisationAdmin("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")).isTrue();
    }
}
