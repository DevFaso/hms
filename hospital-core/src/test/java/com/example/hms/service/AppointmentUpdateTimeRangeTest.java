package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D727 sibling: PUT /appointments/{id} refuses an end at or before the start. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Staff appointment edit: the end must be after the start")
class AppointmentUpdateTimeRangeTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private AppointmentServiceImpl service;

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({"10:00,09:30", "10:00,10:00"})
    void endNotAfterStartIsRefused(String start, String end) {
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("super.admin");
        Role role = new Role();
        role.setCode("ROLE_SUPER_ADMIN");
        UserRole link = new UserRole();
        link.setRole(role);
        admin.setUserRoles(Set.of(link));
        when(userRepository.findByUsername("super.admin")).thenReturn(Optional.of(admin));

        Appointment existing = new Appointment();
        existing.setId(UUID.randomUUID());
        existing.setHospital(new Hospital());
        when(appointmentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setPatientId(UUID.randomUUID());
        request.setStaffId(UUID.randomUUID());
        request.setHospitalId(UUID.randomUUID());
        request.setAppointmentDate(LocalDate.now().plusDays(1));
        request.setStartTime(LocalTime.parse(start));
        request.setEndTime(LocalTime.parse(end));

        UUID existingId = existing.getId();
        assertThatThrownBy(() -> service.updateAppointment(existingId, request, Locale.ENGLISH, "super.admin"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("appointment.timeRange.invalid");
        verify(appointmentRepository, never()).save(any());
        verify(patientRepository, never()).findById(any());
    }
}
