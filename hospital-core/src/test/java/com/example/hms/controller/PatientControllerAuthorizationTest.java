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
     * Of those, only the pharmacist looks patients up WITHOUT reading the
     * chart. The radiologist and physiotherapist gained chart access in role
     * audit D7 once all five of the chart page's layers were widened together
     * (see ConsultingClinicianChartAccessTest).
     */
    private static final List<String> PICKER_BUT_NOT_CHART = List.of("ROLE_PHARMACIST");

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
    @DisplayName("looking patients up is not the same as reading the chart")
    void pharmacistLooksUpWithoutReadingTheChart() {
        // The SecurityConfig GET /patients matcher must admit every picker role
        // — it also covers /patients/search and /patients/lookup — so the
        // controller is the layer that keeps the chart itself narrower. A
        // pharmacist resolves a patient to dispense against; that is not a
        // reason to hand them the whole record.
        assertThat(preAuthorizeOf("getAllPatients")).doesNotContain(PICKER_BUT_NOT_CHART);
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
