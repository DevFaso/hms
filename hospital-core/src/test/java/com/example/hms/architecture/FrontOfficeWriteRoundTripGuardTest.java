package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.controller.AppointmentController;
import com.example.hms.controller.PatientController;
import com.example.hms.controller.PatientInsuranceController;
import com.example.hms.controller.ReceptionController;
import com.example.hms.controller.UserController;
import com.example.hms.controller.UserRoleHospitalAssignmentController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * QA D923 guard. {@code PUT /appointments/{id}} answered 200 and stored
 * nothing (the entity was refreshed before its edits were flushed), and no
 * test wrote then read it back. Every PUT and PATCH handler of the
 * front-office controllers must name the integration test that writes
 * through it and reads the stored row back; a new handler fails this test
 * until it has one. A handler served by another branch's surface is listed
 * with the reason.
 */
class FrontOfficeWriteRoundTripGuardTest {

    private static final Path TESTS = Paths.get("src/test/java/com/example/hms");

    private static final List<Class<?>> CONTROLLERS = List.of(
        AppointmentController.class, PatientController.class, PatientInsuranceController.class,
        ReceptionController.class, UserController.class, UserRoleHospitalAssignmentController.class);

    /** Controller#handler → the test (file#method) that writes through it and reads the row back. */
    private static final Map<String, String> ROUND_TRIPS = Map.ofEntries(
        Map.entry("AppointmentController#updateAppointment",
            "controller/FrontDeskRoundTripIT.java#aReschedulePersists"),
        Map.entry("AppointmentController#updateAppointmentStatus",
            "controller/FrontDeskRoundTripIT.java#aPastScheduledAppointmentCanBeMarkedAbsent"),
        Map.entry("PatientController#updatePatient",
            "controller/FrontDeskRoundTripIT.java#aPatientPutPersists"),
        Map.entry("PatientController#patchPatient",
            "controller/FrontDeskRoundTripIT.java#theDeskCorrectsDemographics"),
        Map.entry("PatientInsuranceController#upsertAndLink",
            "controller/FrontDeskRoundTripIT.java#linkingANewPolicyWorks"),
        Map.entry("PatientInsuranceController#linkById",
            "controller/FrontDeskRoundTripIT.java#insuranceEditsPersist"),
        Map.entry("PatientInsuranceController#updatePatientInsurance",
            "controller/FrontDeskRoundTripIT.java#insuranceEditsPersist"),
        Map.entry("ReceptionController#updateEncounterStatus",
            "controller/FrontDeskRoundTripIT.java#aFlowBoardMovePersists"),
        Map.entry("UserController#updateUser",
            "integration/UserEndpointAuthorizationIT.java#superAdminUserList"),
        Map.entry("UserController#restoreUser",
            "integration/UserEndpointAuthorizationIT.java#superAdminUserList"),
        Map.entry("UserRoleHospitalAssignmentController#updateAssignment",
            "controller/FrontDeskRoundTripIT.java#anAssignmentEditPersists"),
        Map.entry("UserRoleHospitalAssignmentController#deactivateAssignment",
            "controller/SuperAdminContinuityIT.java#anotherSuperAdminsRowCanStillBeRemovedWhileOneRemains"));

    /** Handlers on these controllers that belong to another surface, and why. */
    private static final Map<String, String> OTHER_SURFACES = Map.of(
        "PatientController#updatePatientAllergy",
            "clinical chart (allergies), not the front office: written by clinicians",
        "PatientController#updatePatientDiagnosis",
            "clinical chart (problem list), not the front office: written by doctors");

    @Test
    void everyFrontOfficeWriteHandlerHasARoundTripTest() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method m : controller.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(PutMapping.class) && !m.isAnnotationPresent(PatchMapping.class)) {
                    continue;
                }
                String key = controller.getSimpleName() + "#" + m.getName();
                if (OTHER_SURFACES.containsKey(key)) {
                    continue;
                }
                String target = ROUND_TRIPS.get(key);
                if (target == null) {
                    missing.add(key + " has no round-trip test");
                    continue;
                }
                String[] parts = target.split("#");
                String src = Files.readString(TESTS.resolve(parts[0]), StandardCharsets.UTF_8);
                if (!src.contains("void " + parts[1] + "(")) {
                    missing.add(key + " points at a missing test " + target);
                }
            }
        }
        assertThat(missing).as("PUT/PATCH handlers without a write-then-read-back test (QA D923)").isEmpty();
    }
}
