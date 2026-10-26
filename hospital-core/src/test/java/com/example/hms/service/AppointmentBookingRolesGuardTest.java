package com.example.hms.service;

import com.example.hms.controller.AppointmentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D823 guard: the roles {@code POST /appointments} admits and the roles the
 * service books with AS STAFF are one list. A role the controller lets in but
 * the service does not name is taken for a patient booking for themselves, and
 * its booking for anyone else answers 404 "patient not found" — which is how a
 * midwife, admitted by the controller and by the portal's action map, could not
 * book the prenatal visit the calendar offered her.
 */
@DisplayName("QA D823: every role POST /appointments admits, except PATIENT, books as staff")
class AppointmentBookingRolesGuardTest {

    private static final Pattern QUOTED_ROLE = Pattern.compile("'([A-Z_]+)'");

    private static Set<String> controllerBookRoles() throws ReflectiveOperationException {
        Field field = AppointmentController.class.getDeclaredField("APPOINTMENT_BOOK_ROLES");
        field.setAccessible(true);
        String expression = (String) field.get(null);
        Set<String> roles = new LinkedHashSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(expression);
        while (matcher.find()) {
            roles.add("ROLE_" + matcher.group(1));
        }
        return roles;
    }

    @Test
    @DisplayName("each non-patient booking role of the controller is a staff booking role of the service")
    void controllerAndServiceAgree() throws ReflectiveOperationException {
        Set<String> admitted = controllerBookRoles();
        assertThat(admitted).as("the controller expression parsed").contains("ROLE_MIDWIFE", "ROLE_PATIENT");

        admitted.remove("ROLE_PATIENT");

        assertThat(admitted).isSubsetOf(AppointmentServiceImpl.STAFF_BOOKING_ROLES);
    }

    @Test
    @DisplayName("PATIENT is never a staff booking role (a patient books only for themselves)")
    void patientIsNotStaff() {
        assertThat(AppointmentServiceImpl.STAFF_BOOKING_ROLES).doesNotContain("ROLE_PATIENT");
    }
}
