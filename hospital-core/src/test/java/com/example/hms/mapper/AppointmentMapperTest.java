package com.example.hms.mapper;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AppointmentMapperTest {

    private final AppointmentMapper mapper = new AppointmentMapper();

    @Test
    void toAppointmentResponseDtoReturnsNullForNullAppointment() {
        assertThat(mapper.toAppointmentResponseDTO(null)).isNull();
    }

    @Test
    void toAppointmentResponseDtoMapsNestedEntities() {
        UUID appointmentId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID createdById = UUID.randomUUID();

        User patientUser = User.builder()
            .username("patient1")
            .passwordHash("hash")
            .email("patient@example.com")
            .phoneNumber("1234567890")
            .firstName("John")
            .lastName("Doe")
            .build();
        patientUser.setId(UUID.randomUUID());

        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Doe");
        patient.setUser(patientUser);

        User staffUser = User.builder()
            .username("doctor1")
            .passwordHash("hash")
            .email("doctor@example.com")
            .phoneNumber("0987654321")
            .firstName("Martha")
            .lastName("Jones")
            .build();
        staffUser.setId(UUID.randomUUID());

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setUser(staffUser);

        Hospital hospital = Hospital.builder()
            .name("Central Hospital")
            .code("CH01")
            .address("123 Health St")
            .city("Medicity")
            .state("Wellness")
            .zipCode("12345")
            .country("Careland")
            .province("")
            .region(null)
            .sector("Sector 7")
            .poBox("")
            .build();
        hospital.setId(hospitalId);

        User createdBy = User.builder()
            .username("admin1")
            .passwordHash("hash")
            .email("admin@example.com")
            .phoneNumber("1112223333")
            .firstName("Alice")
            .lastName("Smith")
            .build();
        createdBy.setId(createdById);

        Department department = new Department();
        department.setId(departmentId);

        Appointment appointment = Appointment.builder()
            .appointmentDate(LocalDate.of(2025, 5, 1))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .status(AppointmentStatus.CONFIRMED)
            .reason("Routine checkup")
            .notes("Bring medical reports")
            .patient(patient)
            .staff(staff)
            .hospital(hospital)
            .department(department)
            .assignment(UserRoleHospitalAssignment.builder().build())
            .createdBy(createdBy)
            .build();
        appointment.setId(appointmentId);
        appointment.setCreatedAt(LocalDateTime.of(2025, 4, 20, 12, 0));
        appointment.setUpdatedAt(LocalDateTime.of(2025, 4, 21, 9, 30));

        AppointmentResponseDTO dto = mapper.toAppointmentResponseDTO(appointment);

        assertThat(dto.getId()).isEqualTo(appointmentId);
        assertThat(dto.getDepartmentId()).isEqualTo(departmentId);
        assertThat(dto.getPatientId()).isEqualTo(patientId);
        assertThat(dto.getPatientName()).isEqualTo("John Doe");
        assertThat(dto.getPatientEmail()).isEqualTo("patient@example.com");
        assertThat(dto.getPatientPhone()).isEqualTo("1234567890");
        assertThat(dto.getStaffId()).isEqualTo(staffId);
        assertThat(dto.getStaffName()).isEqualTo("Martha Jones");
        assertThat(dto.getStaffEmail()).isEqualTo("doctor@example.com");
        assertThat(dto.getHospitalId()).isEqualTo(hospitalId);
        assertThat(dto.getHospitalName()).isEqualTo("Central Hospital");
        assertThat(dto.getHospitalAddress()).contains("123 Health St");
        assertThat(dto.getHospitalAddress()).doesNotEndWith(", ");
        assertThat(dto.getCreatedById()).isEqualTo(createdById);
        assertThat(dto.getCreatedByName()).isEqualTo("Alice Smith");
        assertThat(dto.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(dto.getCreatedAt()).isEqualTo(appointment.getCreatedAt());
        assertThat(dto.getUpdatedAt()).isEqualTo(appointment.getUpdatedAt());
    }

    @Test
    void toAppointmentBuildsEntityFromDto() {
        AppointmentRequestDTO dto = AppointmentRequestDTO.builder()
            .appointmentDate(LocalDate.of(2025, 6, 15))
            .startTime(LocalTime.of(11, 0))
            .endTime(LocalTime.of(12, 30))
            .status(AppointmentStatus.SCHEDULED)
            .reason("Consultation")
            .notes("Fasting required")
            .build();

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        Hospital hospital = Hospital.builder().name("Care Center").code("CC01").build();
        hospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();
        assignment.setId(UUID.randomUUID());
        User createdBy = User.builder().username("creator").passwordHash("hash")
            .email("creator@example.com").phoneNumber("5555555555").build();
        createdBy.setId(UUID.randomUUID());

        Appointment result = mapper.toAppointment(dto, patient, staff, hospital, assignment, createdBy);

        assertThat(result.getAppointmentDate()).isEqualTo(dto.getAppointmentDate());
        assertThat(result.getPatient()).isSameAs(patient);
        assertThat(result.getStaff()).isSameAs(staff);
        assertThat(result.getHospital()).isSameAs(hospital);
        assertThat(result.getAssignment()).isSameAs(assignment);
        assertThat(result.getCreatedBy()).isSameAs(createdBy);
        assertThat(result.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void updateAppointmentFromDtoOverwritesMutableFields() {
        Hospital existingHospital = Hospital.builder()
            .name("Old Hospital")
            .code("OH")
            .build();
        existingHospital.setId(UUID.randomUUID());

        Appointment appointment = Appointment.builder()
            .appointmentDate(LocalDate.of(2025, 7, 1))
            .startTime(LocalTime.of(8, 0))
            .endTime(LocalTime.of(9, 0))
            .status(AppointmentStatus.SCHEDULED)
            .reason("Initial reason")
            .notes("Initial notes")
            .patient(new Patient())
            .staff(new Staff())
            .hospital(existingHospital)
            .assignment(UserRoleHospitalAssignment.builder().build())
            .build();

        AppointmentRequestDTO dto = AppointmentRequestDTO.builder()
            .appointmentDate(LocalDate.of(2025, 7, 2))
            .startTime(LocalTime.of(10, 0))
            .endTime(LocalTime.of(11, 30))
            .status(AppointmentStatus.CANCELLED)
            .reason("Updated reason")
            .notes("Updated notes")
            .build();

        Patient newPatient = new Patient();
        newPatient.setId(UUID.randomUUID());
        Staff newStaff = new Staff();
        newStaff.setId(UUID.randomUUID());
        Hospital newHospital = Hospital.builder().name("New Hospital").code("NH").build();
        newHospital.setId(UUID.randomUUID());

        mapper.updateAppointmentFromDto(dto, appointment, newPatient, newStaff, newHospital);

        assertThat(appointment.getAppointmentDate()).isEqualTo(dto.getAppointmentDate());
        assertThat(appointment.getStartTime()).isEqualTo(dto.getStartTime());
        assertThat(appointment.getEndTime()).isEqualTo(dto.getEndTime());
        assertThat(appointment.getStatus()).isEqualTo(dto.getStatus());
        assertThat(appointment.getReason()).isEqualTo("Updated reason");
        assertThat(appointment.getNotes()).isEqualTo("Updated notes");
        assertThat(appointment.getPatient()).isSameAs(newPatient);
        assertThat(appointment.getStaff()).isSameAs(newStaff);
        assertThat(appointment.getHospital()).isSameAs(newHospital);
    }

    @Test
    void updateAppointmentFromDtoHandlesNullInputs() {
        Appointment appointment = new Appointment();

        assertThatNoException().isThrownBy(() -> mapper.updateAppointmentFromDto(null, appointment, null, null, null));
        assertThatNoException().isThrownBy(() -> mapper.updateAppointmentFromDto(new AppointmentRequestDTO(), null, null, null, null));
    }
}
