package com.example.hms.service;

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

/**
 * QA D727 sibling, superseded by QA D1060: PUT /appointments/{id} used to
 * refuse an end at or before the start. The end is no longer the caller's: the
 * slot rules set it (AppointmentSlotPolicy), so a backwards end can no longer
 * be stored and is not refused; the edit goes on to its next check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Staff appointment edit: the caller's end time is not the visit's end (QA D1060)")
class AppointmentUpdateTimeRangeTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRepository userRepository;

    /** QA D1060: the slot rules, stubbed to their 30-minute fallback (SlotPolicyStubs). */
    @org.mockito.Mock
    private com.example.hms.service.scheduling.AppointmentSlotPolicy slotPolicy;

    @org.junit.jupiter.api.BeforeEach
    void stubTheSlotRules() {
        com.example.hms.testsupport.SlotPolicyStubs.defaultLength(slotPolicy);
    }

    @InjectMocks private AppointmentServiceImpl service;

    @ParameterizedTest(name = "{0} -> {1} is not a time-range refusal any more")
    @CsvSource({"10:00,09:30", "10:00,10:00"})
    void theCallersEndTimeIsNotJudged(String start, String end) {
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
        // The edit passes the old time-range check and stops at the next one
        // (the unknown patient), never on the caller's end time.
        assertThatThrownBy(() -> service.updateAppointment(existingId, request, Locale.ENGLISH, "super.admin"))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class)
            .hasMessageNotContaining("appointment.timeRange.invalid");
        verify(appointmentRepository, never()).save(any());
    }
}
