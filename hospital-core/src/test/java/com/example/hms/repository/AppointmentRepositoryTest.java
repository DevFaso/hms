package com.example.hms.repository;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the {@link Appointment} entity's {@code @PrePersist/@PreUpdate}
 * validation logic, invoked via reflection (same pattern as EncounterTest).
 */
class AppointmentRepositoryTest {

    private Hospital hospital;
    private Staff staff;
    private Patient patient;
    private Department department;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        UUID hospitalId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setHospital(hospital);

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        department = new Department();
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
    }

    private Appointment validAppointment() {
        return Appointment.builder()
                .patient(patient)
                .staff(staff)
                .hospital(hospital)
                .department(department)
                .assignment(assignment)
                .appointmentDate(LocalDate.of(2025, 7, 1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(9, 30))
                .status(AppointmentStatus.SCHEDULED)
                .build();
    }

    private void invokeValidate(Appointment appointment) throws Exception {
        Method m = Appointment.class.getDeclaredMethod("validate");
        m.setAccessible(true);
        try {
            m.invoke(appointment);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    // ─── Happy path ──────────────────────────────────────────────

    @Test
    void happyPath_validatesSuccessfully() throws Exception {
        assertThatNoException().isThrownBy(() -> invokeValidate(validAppointment()));
    }

    // ─── Time validation ─────────────────────────────────────────

    @Nested
    class TimeValidation {

        @Test
        void endTimeBeforeStartTime_throws() {
            Appointment a = validAppointment();
            a.setStartTime(LocalTime.of(10, 0));
            a.setEndTime(LocalTime.of(9, 0));
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("end_time must be after start_time");
        }

        @Test
        void endTimeEqualsStartTime_throws() {
            Appointment a = validAppointment();
            a.setStartTime(LocalTime.of(10, 0));
            a.setEndTime(LocalTime.of(10, 0));
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("end_time must be after start_time");
        }

        @Test
        void nullStartTime_throws() {
            Appointment a = validAppointment();
            a.setStartTime(null);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("end_time must be after start_time");
        }

        @Test
        void nullEndTime_throws() {
            Appointment a = validAppointment();
            a.setEndTime(null);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("end_time must be after start_time");
        }
    }

    // ─── Staff-hospital validation ───────────────────────────────

    @Nested
    class StaffHospitalValidation {

        @Test
        void staffNull_throws() {
            Appointment a = validAppointment();
            a.setStaff(null);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Staff does not belong to selected hospital");
        }

        @Test
        void staffHospitalNull_throws() {
            Appointment a = validAppointment();
            Staff s = new Staff();
            s.setHospital(null);
            a.setStaff(s);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Staff does not belong to selected hospital");
        }

        @Test
        void staffHospitalMismatch_throws() {
            Appointment a = validAppointment();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            Staff s = new Staff();
            s.setHospital(other);
            a.setStaff(s);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Staff does not belong to selected hospital");
        }
    }

    // ─── Assignment-hospital validation ──────────────────────────

    @Nested
    class AssignmentHospitalValidation {

        @Test
        void assignmentNull_throws() {
            Appointment a = validAppointment();
            a.setAssignment(null);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match hospital");
        }

        @Test
        void assignmentHospitalNull_throws() {
            Appointment a = validAppointment();
            UserRoleHospitalAssignment asgn = new UserRoleHospitalAssignment();
            asgn.setHospital(null);
            a.setAssignment(asgn);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match hospital");
        }

        @Test
        void assignmentHospitalMismatch_throws() {
            Appointment a = validAppointment();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            UserRoleHospitalAssignment asgn = new UserRoleHospitalAssignment();
            asgn.setHospital(other);
            a.setAssignment(asgn);
            assertThatThrownBy(() -> invokeValidate(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assignment.hospital must match hospital");
        }
    }

    // ─── Default status assignment ───────────────────────────────

    @Nested
    class DefaultStatus {

        @Test
        void nullStatus_defaultsToScheduled() throws Exception {
            Appointment a = validAppointment();
            a.setStatus(null);
            invokeValidate(a);
            assertThat(a.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        }

        @Test
        void existingStatus_isPreserved() throws Exception {
            Appointment a = validAppointment();
            a.setStatus(AppointmentStatus.COMPLETED);
            invokeValidate(a);
            assertThat(a.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        }
    }
}