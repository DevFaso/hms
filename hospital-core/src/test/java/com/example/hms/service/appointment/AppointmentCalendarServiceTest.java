package com.example.hms.service.appointment;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.appointment.AppointmentCalendarEventDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AppointmentServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for {@link AppointmentService#getCalendarEvents} — the
 * date-range slice behind the Cadence calendar grid. Other
 * AppointmentServiceImpl methods are exercised by their respective
 * higher-level / controller tests.
 */
class AppointmentCalendarServiceTest {

    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);

    private AppointmentServiceImpl service() {
        // The impl is @RequiredArgsConstructor; field declaration order is
        // emailService first, then appointmentRepository, then collaborators
        // we don't touch in this test, then the AppointmentLinkProperties
        // bean added in PR #315.
        return new AppointmentServiceImpl(
            null,                       // emailService
            appointmentRepository,      // appointmentRepository
            null,                       // patientRepository
            null,                       // staffRepository
            null,                       // hospitalRepository
            null,                       // assignmentRepository
            null,                       // appointmentMapper
            null,                       // messageSource
            null,                       // userRepository
            null,                       // staffAvailabilityService
            null,                       // departmentRepository
            null,                       // slotInventoryService (P3 #22)
            null,                       // webhookPublisher (Tier 2 item 45)
            null                        // appointmentLinks (AppointmentLinkProperties, PR #315)
        );
    }

    private static Appointment build(UUID id, UUID staffId, UUID patientId,
                                     LocalDate date, LocalTime start, LocalTime end) {
        Appointment a = new Appointment();
        a.setId(id);
        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setName("Dr Provider");
        a.setStaff(staff);
        Patient p = new Patient();
        p.setId(patientId);
        p.setFirstName("Alice");
        p.setLastName("Patient");
        a.setPatient(p);
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        a.setHospital(h);
        a.setAppointmentDate(date);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(AppointmentStatus.SCHEDULED);
        a.setReason("Follow-up");
        return a;
    }

    @Test
    void emptyResultWhenHospitalIdMissing() {
        AppointmentService svc = service();
        assertThat(svc.getCalendarEvents(null, LocalDate.now(), LocalDate.now(), null, null, null)).isEmpty();
        verify(appointmentRepository, never())
            .findByHospital_IdAndAppointmentDateBetween(any(), any(), any());
    }

    @Test
    void emptyResultWhenFromAfterTo() {
        AppointmentService svc = service();
        UUID hospitalId = UUID.randomUUID();
        assertThat(svc.getCalendarEvents(
            hospitalId, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1), null, null, null)).isEmpty();
        verify(appointmentRepository, never())
            .findByHospital_IdAndAppointmentDateBetween(any(), any(), any());
    }

    @Test
    void unscopedRangeQueryWhenStaffIdNotProvided() {
        AppointmentService svc = service();
        UUID hospitalId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 7);

        Appointment a = build(UUID.randomUUID(), providerId, patientId,
            LocalDate.of(2026, 5, 3), LocalTime.of(9, 0), LocalTime.of(9, 30));
        when(appointmentRepository.findByHospital_IdAndAppointmentDateBetween(hospitalId, from, to))
            .thenReturn(List.of(a));

        List<AppointmentCalendarEventDTO> events = svc.getCalendarEvents(hospitalId, from, to, null, null, null);

        assertThat(events).hasSize(1);
        AppointmentCalendarEventDTO e = events.get(0);
        assertThat(e.resourceId()).isEqualTo(providerId);
        assertThat(e.patientId()).isEqualTo(patientId);
        assertThat(e.title()).contains("Alice");
        assertThat(e.start()).isEqualTo(LocalDate.of(2026, 5, 3).atTime(9, 0));
        assertThat(e.end()).isEqualTo(LocalDate.of(2026, 5, 3).atTime(9, 30));
        assertThat(e.status()).isEqualTo("SCHEDULED");

        verify(appointmentRepository, never())
            .findByHospital_IdAndStaff_IdAndAppointmentDateBetween(any(), any(), any(), any());
    }

    @Test
    void providerScopedRangeQueryWhenStaffIdProvided() {
        AppointmentService svc = service();
        UUID hospitalId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 7);

        when(appointmentRepository.findByHospital_IdAndStaff_IdAndAppointmentDateBetween(
            hospitalId, providerId, from, to)).thenReturn(List.of());

        List<AppointmentCalendarEventDTO> events =
            svc.getCalendarEvents(hospitalId, from, to, providerId, null, null);

        assertThat(events).isEmpty();
        verify(appointmentRepository, never())
            .findByHospital_IdAndAppointmentDateBetween(any(), any(), any());
    }

    @Test
    void mappingTolerantOfNullStaffOrPatient() {
        AppointmentService svc = service();
        UUID hospitalId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        Appointment naked = new Appointment();
        naked.setId(UUID.randomUUID());
        Hospital h = new Hospital();
        h.setId(hospitalId);
        naked.setHospital(h);
        naked.setAppointmentDate(today);
        naked.setStartTime(LocalTime.of(8, 0));
        naked.setEndTime(LocalTime.of(8, 30));
        naked.setStatus(null);
        when(appointmentRepository.findByHospital_IdAndAppointmentDateBetween(hospitalId, today, today))
            .thenReturn(List.of(naked));

        List<AppointmentCalendarEventDTO> events = svc.getCalendarEvents(hospitalId, today, today, null, null, null);

        assertThat(events).hasSize(1);
        AppointmentCalendarEventDTO e = events.get(0);
        assertThat(e.resourceId()).isNull();
        assertThat(e.patientId()).isNull();
        assertThat(e.title()).isEqualTo("Appointment");
        assertThat(e.status()).isNull();
    }
}
