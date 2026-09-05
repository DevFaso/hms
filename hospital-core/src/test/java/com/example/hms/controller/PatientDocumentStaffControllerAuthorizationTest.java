package com.example.hms.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role gate is a {@code @PreAuthorize} expression on every route. This
 * pins its shape: the same roles as the portal tab, no lab roles (the chart's
 * lab surface is elsewhere), and — unlike the patient-side controller — never
 * ROLE_PATIENT, whose reads stay ownership-checked on {@code /me}.
 */
class PatientDocumentStaffControllerAuthorizationTest {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST",
        "ROLE_RECEPTIONIST", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"})
    void chartRolesMayRead(String role) {
        assertThat(PatientDocumentStaffController.READ_ROLES).contains("'" + role + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_PATIENT", "ROLE_LAB_SCIENTIST", "ROLE_LAB_TECHNICIAN", "ROLE_ACCOUNTANT"})
    void otherRolesMayNot(String role) {
        assertThat(PatientDocumentStaffController.READ_ROLES).doesNotContain("'" + role + "'");
    }

    @Test
    void everyRouteCarriesTheSameGate() throws NoSuchMethodException {
        for (String name : new String[] {"list", "get", "download"}) {
            var method = java.util.Arrays.stream(PatientDocumentStaffController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            assertThat(gate).as(name).isNotNull();
            assertThat(gate.value()).isEqualTo(PatientDocumentStaffController.READ_ROLES);
        }
    }
}
