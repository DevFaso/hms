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
     * Roles that reach a page embedding {@code <app-patient-picker>}: the
     * pharmacist (/medication-history), the radiologist (/imaging) and the
     * physiotherapist (/treatment-plans).
     */
    private static final List<String> PICKER_ROLES =
        List.of("ROLE_PHARMACIST", "ROLE_RADIOLOGIST", "ROLE_PHYSIOTHERAPIST");

    /**
     * All three read the chart now. The radiologist and physiotherapist gained
     * it in role audit D7 once all five of the chart page's layers were widened
     * together (see ConsultingClinicianChartAccessTest); the pharmacist in E9
     * #69, because the verification gate (V139) needs the problem list, the
     * vitals and the results — renal function decides doses — and the picker,
     * the medication tab and chart review had assumed the pharmacist on the
     * chart all along while this list refused the demographics page.
     */
    private static final List<String> CHART_READERS_FROM_THE_PICKER = PICKER_ROLES;

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
        assertThat(search).contains(PICKER_ROLES);
    }

    @Test
    @DisplayName("every picker role reads the chart it resolves a patient into")
    void pickerRolesReadTheChart() {
        // The SecurityConfig GET /patients matcher admits every picker role — it
        // also covers /patients/search and /patients/lookup — and since E9 #69
        // the controller does too: a role that can find a patient can open the
        // record it needs for its own act (report, session, verification). The
        // chart's own sub-resources stay gated per surface (ClinicalMatrixGapsTest).
        assertThat(preAuthorizeOf("getAllPatients")).contains(CHART_READERS_FROM_THE_PICKER);
        assertThat(preAuthorizeOf("getPatientById")).contains(CHART_READERS_FROM_THE_PICKER);
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
