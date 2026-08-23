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
 * Drift guard for role audit decision D7.
 *
 * <p>The patient chart is not one endpoint. Opening it fans out to five
 * independently guarded reads — the patient, their vitals, their encounters,
 * their appointments, and the caller's hospital scope — and those five lists
 * were maintained by hand, so they drifted. Admitting a role to only some of
 * them does not open the chart; it moves the 403 from the route to four empty
 * panels, which is strictly worse because the page now *looks* available.
 *
 * <p>So this asserts the whole set together. If a future change widens one
 * layer and forgets the others, or narrows one, it fails here rather than in
 * front of a clinician.
 */
class ConsultingClinicianChartAccessTest {

    /**
     * Clinicians who read the chart but do not own it: radiologists (clinical
     * indication, contrast safety), anaesthetists (pre-operative assessment),
     * physiotherapists (rehabilitation caseload).
     */
    private static final List<String> CONSULTING = List.of(
        "RADIOLOGIST", "ANESTHESIOLOGIST", "PHYSIOTHERAPIST");

    private static String preAuthorizeOf(Class<?> controller, String methodName) {
        return Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .map(m -> m.getAnnotation(PreAuthorize.class))
            .filter(Objects::nonNull)
            .map(PreAuthorize::value)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No @PreAuthorize on " + controller.getSimpleName() + "." + methodName));
    }

    private static void assertAdmitsConsulting(Class<?> controller, String methodName) {
        String expression = preAuthorizeOf(controller, methodName);
        for (String role : CONSULTING) {
            assertThat(expression)
                .as("%s.%s must admit %s", controller.getSimpleName(), methodName, role)
                .contains(role);
        }
    }

    @Test
    @DisplayName("chart read admits the consulting clinicians")
    void patientReadAdmitsConsultingClinicians() {
        assertAdmitsConsulting(PatientController.class, "getAllPatients");
        assertAdmitsConsulting(PatientController.class, "getPatientById");
    }

    @Test
    @DisplayName("every panel the chart page loads admits them too")
    void everyChartPanelAdmitsConsultingClinicians() {
        // The point of D7: these must move as a set. Vitals, encounters and
        // appointments are the three panels that would otherwise 403 behind a
        // route the caller can now reach.
        for (Method m : PatientVitalSignController.class.getDeclaredMethods()) {
            if (m.getAnnotation(PreAuthorize.class) == null) continue;
            if (!m.getName().toLowerCase().contains("get")) continue;
            assertAdmitsConsulting(PatientVitalSignController.class, m.getName());
        }
        assertAdmitsConsulting(EncounterController.class, "list");
        assertAdmitsConsulting(EncounterController.class, "getById");
        assertAdmitsConsulting(AppointmentController.class, "getAppointmentsByPatientId");
    }

    @Test
    @DisplayName("read access does not leak into writes")
    void consultingCliniciansCannotWriteTheChart() {
        // They read the chart and write only their own specialty surface, so
        // recording vitals, opening encounters and booking appointments stay
        // closed. Booking and encounter-create each carry their OWN constant
        // precisely so widening a read list cannot leak into them — both were
        // sharing a literal with a read endpoint before D7.
        String recordVitals = preAuthorizeOf(PatientVitalSignController.class, "recordVital");
        String bookAppointment = preAuthorizeOf(AppointmentController.class, "createAppointment");
        String createEncounter = preAuthorizeOf(EncounterController.class, "create");

        for (String role : CONSULTING) {
            assertThat(recordVitals).as("recordVital").doesNotContain(role);
            assertThat(bookAppointment).as("createAppointment").doesNotContain(role);
            assertThat(createEncounter).as("create encounter").doesNotContain(role);
        }
    }
}
