package com.example.hms.service;

import com.example.hms.controller.AppointmentController;
import com.example.hms.controller.ConsultationController;
import com.example.hms.controller.ImagingOrderController;
import com.example.hms.controller.ImagingResultController;
import com.example.hms.controller.ProcedureOrderController;
import com.example.hms.controller.UltrasoundController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each set in {@link PatientSubjectReaderRoles} is exactly its endpoint's
 * {@code @PreAuthorize} minus {@code ROLE_PATIENT} — read back from the
 * compiled annotation, the string Spring evaluates, so a constant such as
 * {@code APPOINTMENT_READ_ROLES} (which concatenates
 * {@code CONSULTING_CLINICIANS_ROLES}) is asserted as it is actually inlined.
 *
 * <p>Both directions of drift are defects, as {@code EncounterReaderRolesMirrorTest}
 * sets out: a role the annotation admits but the set omits is refused its
 * colleagues' records; a role the set names but the annotation does not admit
 * enters only through {@code ROLE_PATIENT} and would be reclassified as staff,
 * skipping the ownership test.
 */
@DisplayName("Each patient-subject reader set is exactly its endpoint's annotation minus ROLE_PATIENT")
class PatientSubjectReaderRolesMirrorTest {

    /** A quoted role name, bare ({@code hasAnyRole}) or prefixed ({@code hasAnyAuthority}). */
    private static final Pattern ROLE = Pattern.compile("'((?:ROLE_)?[A-Z_]+)'");

    static Stream<Arguments> readers() {
        return Stream.of(
            Arguments.of(ConsultationController.class, "getConsultationsForPatient", "/patient/{patientId}",
                PatientSubjectReaderRoles.CONSULTATIONS_BY_PATIENT),
            Arguments.of(ImagingOrderController.class, "getOrdersByPatient", "/orders/patient/{patientId}",
                PatientSubjectReaderRoles.IMAGING_ORDERS_BY_PATIENT),
            Arguments.of(ImagingResultController.class, "getReport", "/{reportId}",
                PatientSubjectReaderRoles.IMAGING_REPORT_READS),
            Arguments.of(ImagingResultController.class, "getLatestReportForOrder", "/order/{orderId}",
                PatientSubjectReaderRoles.IMAGING_REPORT_READS),
            Arguments.of(ProcedureOrderController.class, "getProcedureOrder", "/{orderId}",
                PatientSubjectReaderRoles.PROCEDURE_ORDER_READS),
            Arguments.of(ProcedureOrderController.class, "getProcedureOrdersForPatient", "/patient/{patientId}",
                PatientSubjectReaderRoles.PROCEDURE_ORDER_READS),
            Arguments.of(UltrasoundController.class, "getOrderById", "/orders/{orderId}",
                PatientSubjectReaderRoles.ULTRASOUND_READS),
            Arguments.of(UltrasoundController.class, "getOrdersByPatientId", "/orders/patient/{patientId}",
                PatientSubjectReaderRoles.ULTRASOUND_READS),
            Arguments.of(UltrasoundController.class, "getReportById", "/reports/{reportId}",
                PatientSubjectReaderRoles.ULTRASOUND_READS),
            Arguments.of(UltrasoundController.class, "getReportByOrderId", "/reports/order/{orderId}",
                PatientSubjectReaderRoles.ULTRASOUND_READS),
            Arguments.of(AppointmentController.class, "getAppointmentById", "/{id}",
                PatientSubjectReaderRoles.APPOINTMENT_READS),
            Arguments.of(AppointmentController.class, "getAppointmentsByPatientId", "/patients/{patientId}",
                PatientSubjectReaderRoles.APPOINTMENT_READS),
            Arguments.of(AppointmentController.class, "getAppointmentsByPatientUsername",
                "/patients/username/{patientUsername}", PatientSubjectReaderRoles.APPOINTMENT_READS));
    }

    @ParameterizedTest(name = "{0}.{1} ({2})")
    @MethodSource("readers")
    void theRoleSetMirrorsTheAnnotation(Class<?> controller, String handler, String path, Set<String> nonSubjectRoles) {
        Method method = handler(controller, handler);

        // Pinned to its route as well as its name, so a rename that moved the
        // name onto a different endpoint cannot pass silently.
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).as("%s must still be a GET handler", handler).isNotNull();
        assertThat(mapping.value()).as("%s must still serve %s", handler, path).containsExactly(path);

        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).as("%s must still carry its own annotation", handler).isNotNull();

        Set<String> admitted = new TreeSet<>();
        Matcher role = ROLE.matcher(preAuthorize.value());
        while (role.find()) {
            String name = role.group(1);
            admitted.add(name.startsWith("ROLE_") ? name : "ROLE_" + name);
        }
        // The guard exists because the annotation admits patients; if that
        // ever stops being true the endpoint needs no subject set at all.
        assertThat(admitted).as("%s must still admit ROLE_PATIENT", handler).contains("ROLE_PATIENT");
        admitted.remove("ROLE_PATIENT");

        assertThat(new TreeSet<>(nonSubjectRoles))
            .as("a role the annotation admits but the set omits is refused its colleagues' records; "
                + "a role the set names but the annotation does not admit enters only through "
                + "ROLE_PATIENT and would be reclassified as staff")
            .isEqualTo(admitted);
    }

    private static Method handler(Class<?> controller, String name) {
        List<Method> matches = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .toList();
        assertThat(matches).as("exactly one %s on %s", name, controller.getSimpleName()).hasSize(1);
        return matches.get(0);
    }
}
