package com.example.hms.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role gate is a {@code @PreAuthorize} expression on every route. This
 * pins its shape: the same roles as the portal tab, no lab roles (the chart's
 * lab surface is elsewhere), and — unlike the patient-side controller — never
 * ROLE_PATIENT, whose reads stay ownership-checked on {@code /me}.
 */
class PatientDocumentStaffControllerAuthorizationTest {

    /** The quoted role names inside {@code hasAnyAuthority('…','…')}. */
    private static Set<String> grantedRoles() {
        return rolesIn(PatientDocumentStaffController.ACCESS_ROLES);
    }

    private static Set<String> rolesIn(String expression) {
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(expression);
        Set<String> roles = new HashSet<>();
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"})
    void chartRolesMayRead(String role) {
        Set<String> granted = grantedRoles();
        assertThat(granted).contains(role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_PATIENT", "ROLE_LAB_SCIENTIST", "ROLE_LAB_TECHNICIAN", "ROLE_ACCOUNTANT",
        "ROLE_PHARMACIST", "ROLE_BILLING_SPECIALIST"}) // QA D112
    void otherRolesMayNot(String role) {
        Set<String> granted = grantedRoles();
        assertThat(granted).isNotEmpty().doesNotContain(role);
    }

    /** QA D1599: reception and billing are admitted, and the service narrows them to insurance. */
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_RECEPTIONIST"})
    void frontDeskIsAdmittedButNeverAsAClinicalReader(String role) {
        assertThat(grantedRoles()).contains(role);
        assertThat(rolesIn(PatientDocumentStaffController.READ_ROLES)).doesNotContain(role);
    }

    /** The service's full-access set is exactly the clinical gate: a drift either way is a defect. */
    @Test
    void serviceClinicalReadersMirrorTheClinicalGate() {
        assertThat(com.example.hms.service.impl.PatientDocumentServiceImpl.CLINICAL_READERS)
            .isEqualTo(rolesIn(PatientDocumentStaffController.READ_ROLES));
        assertThat(grantedRoles()).containsAll(rolesIn(PatientDocumentStaffController.READ_ROLES));
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
            assertThat(gate.value()).isEqualTo(PatientDocumentStaffController.ACCESS_ROLES);
        }
    }
}
