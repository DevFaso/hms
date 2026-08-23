package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard for the 2026-08-23 role audit (D4).
 *
 * <p>SecurityConfig's {@code GET /patients} matcher runs before any
 * {@code @PreAuthorize} and is terminal on first match, so a role admitted by
 * exactly one of the two layers produces a 403 that the annotation cannot
 * explain. These assertions pin the roles the audit resolved, so dropping one
 * from either side fails here instead of silently killing a page.
 */
class PatientControllerAuthorizationTest {

    /**
     * Roles that reach a page embedding {@code <app-patient-picker>} but have no
     * business paging the whole chart: the pharmacist (/medication-history), the
     * radiologist (/imaging) and the physiotherapist (/treatment-plans).
     */
    private static final List<String> PICKER_ONLY_ROLES =
        List.of("ROLE_PHARMACIST", "ROLE_RADIOLOGIST", "ROLE_PHYSIOTHERAPIST");

    private static String preAuthorizeOf(String methodName) {
        return Arrays.stream(PatientController.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .map(m -> m.getAnnotation(PreAuthorize.class))
            .filter(Objects::nonNull)
            .map(PreAuthorize::value)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No @PreAuthorize found on " + methodName));
    }

    @Test
    @DisplayName("list and detail share one role list — a split is how they drifted before")
    void chartReadEndpointsShareOneRoleList() {
        assertThat(preAuthorizeOf("getPatientById")).isEqualTo(preAuthorizeOf("getAllPatients"));
    }

    @Test
    @DisplayName("the shared patient picker admits every role whose pages embed it")
    void pickerAdmitsEveryEmbeddingRole() {
        // /imaging embeds <app-patient-picker> for radiologists and
        // /treatment-plans for physiotherapists (admitted by audit decision C4);
        // both called search/lookup with roles the annotation rejected.
        String search = preAuthorizeOf("searchPatients");
        String lookup = preAuthorizeOf("lookupPatients");
        assertThat(search).isEqualTo(lookup);
        assertThat(search).contains(PICKER_ONLY_ROLES);
    }

    @Test
    @DisplayName("picker-only roles do not get the whole chart")
    void pickerOnlyRolesCannotPageTheChart() {
        // The SecurityConfig GET /patients matcher has to admit them — it also
        // covers /patients/search and /patients/lookup — so the controller is
        // the layer that keeps the chart itself narrower. Granting these roles
        // the chart is D7, and needs the page's vitals/encounters/sharing
        // panels widened too or it just 403s in four places instead of one.
        assertThat(preAuthorizeOf("getAllPatients")).doesNotContain(PICKER_ONLY_ROLES);
    }

    @Test
    @DisplayName("every patient endpoint still carries an authorization annotation")
    void everyMappedEndpointIsAuthorized() {
        List<Method> unannotated = Arrays.stream(PatientController.class.getDeclaredMethods())
            .filter(m -> m.getAnnotation(PreAuthorize.class) == null)
            .filter(m -> Arrays.stream(m.getAnnotations())
                .anyMatch(a -> a.annotationType().getName().startsWith("org.springframework.web.bind.annotation")))
            .toList();
        assertThat(unannotated).isEmpty();
    }
}
