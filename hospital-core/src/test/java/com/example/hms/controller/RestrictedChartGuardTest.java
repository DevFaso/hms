package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8 #54 — restricting a chart and signing off a break-the-glass session are
 * administrative acts: HOSPITAL_ADMIN and SUPER_ADMIN only, no clinical role.
 */
class RestrictedChartGuardTest {

    @Test
    @DisplayName("restricting or lifting a chart is a hospital-admin act")
    void chartRestrictionIsAdministrative() {
        Map<String, String> guards = GuardIndex.guardsOf(PatientController.class);
        String guard = guards.get("POST /{id}/chart-restriction");
        assertThat(guard).as("POST /patients/{id}/chart-restriction is guarded").isNotNull()
            .contains("'ROLE_HOSPITAL_ADMIN'", "'ROLE_SUPER_ADMIN'")
            .doesNotContain("DOCTOR", "NURSE", "MIDWIFE", "RECEPTIONIST", "PHARMACIST");
    }

    @Test
    @DisplayName("signing off a session is a hospital-admin act, like reading the register")
    void reviewIsAdministrative() {
        Map<String, String> guards = GuardIndex.guardsOf(BreakGlassController.class);
        String review = guards.get("PATCH /{sessionId}/review");
        assertThat(review).as("PATCH /break-glass/{sessionId}/review is guarded").isNotNull()
            .contains("'ROLE_HOSPITAL_ADMIN'", "'ROLE_SUPER_ADMIN'")
            .doesNotContain("DOCTOR", "NURSE", "MIDWIFE")
            .isEqualTo(guards.get("GET /audit"));
    }
}
