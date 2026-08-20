package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Notification;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.NotificationPreferenceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AppointmentReminderServiceTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationService notificationService;
    @Mock private SmsService smsService;
    @Mock private MessageSource messageSource;

    private AppointmentReminderService service;

    private Patient patient;
    private User portalUser;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        service = new AppointmentReminderService(
            appointmentRepository, preferenceRepository, notificationService, smsService, messageSource);
        ReflectionTestUtils.setField(service, "leadHours", 24L);
        ReflectionTestUtils.setField(service, "reminderLocale", "fr");

        portalUser = new User();
        portalUser.setId(UUID.randomUUID());
        portalUser.setUsername("awa.traore");

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setUser(portalUser);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("CHU Yalgado");

        LocalDateTime startsAt = LocalDateTime.now().plusHours(4);
        appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setPatient(patient);
        appointment.setHospital(hospital);
        appointment.setAppointmentDate(startsAt.toLocalDate());
        appointment.setStartTime(startsAt.toLocalTime());
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        lenient().when(messageSource.getMessage(eq("sms.appointment.reminder"), any(), any(Locale.class)))
            .thenReturn("Rappel : rendez-vous à CHU Yalgado.");
        lenient().when(appointmentRepository.save(any(Appointment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(preferenceRepository.findByUser_IdAndNotificationType(
            portalUser.getId(), NotificationType.APPOINTMENT_REMINDER))
            .thenReturn(List.of());
    }

    private void feed(Appointment... appointments) {
        when(appointmentRepository.findAwaitingReminder(any(), any(), any()))
            .thenReturn(List.of(appointments));
    }

    @Test
    void remindsInAppAndSmsAndStamps() {
        feed(appointment);

        int reminded = service.sendDueReminders();

        assertThat(reminded).isEqualTo(1);
        verify(notificationService).createNotification(
            contains("Rappel"), eq("awa.traore"), eq("APPOINTMENT_REMINDER"));
        verify(smsService).send(eq("+22670707070"), contains("Rappel"));
        assertThat(appointment.getReminderSentAt()).isNotNull();
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        when(smsService.deliversRealSms()).thenReturn(false);
        feed(appointment);

        int reminded = service.sendDueReminders();

        verify(smsService, never()).send(anyString(), anyString());
        // In-app still fired — the appointment still counts as reminded.
        assertThat(reminded).isEqualTo(1);
        assertThat(appointment.getReminderSentAt()).isNotNull();
    }

    @Test
    void honoursDisabledSmsPreference() {
        when(preferenceRepository.findByUser_IdAndNotificationType(
            portalUser.getId(), NotificationType.APPOINTMENT_REMINDER))
            .thenReturn(List.of(NotificationPreference.builder()
                .user(portalUser)
                .notificationType(NotificationType.APPOINTMENT_REMINDER)
                .channel(NotificationChannel.SMS)
                .enabled(false)
                .build()));
        feed(appointment);

        service.sendDueReminders();

        verify(smsService, never()).send(anyString(), anyString());
        verify(notificationService).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void skipsAppointmentsOutsideTheHourWindowWithoutStamping() {
        LocalDateTime farFuture = LocalDateTime.now().plusHours(48);
        appointment.setAppointmentDate(farFuture.toLocalDate());
        appointment.setStartTime(farFuture.toLocalTime());
        feed(appointment);

        int reminded = service.sendDueReminders();

        assertThat(reminded).isZero();
        assertThat(appointment.getReminderSentAt()).isNull(); // later tick picks it up
        verify(appointmentRepository, never()).save(any(Appointment.class));
    }

    @Test
    void stampsEvenWhenEveryChannelSkips() {
        when(smsService.deliversRealSms()).thenReturn(false);
        patient.setUser(null); // no portal account resolvable

        feed(appointment);

        int reminded = service.sendDueReminders();

        assertThat(reminded).isZero();
        assertThat(appointment.getReminderSentAt()).isNotNull(); // sweep converges
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void notificationFailureDoesNotStopSmsOrStamp() {
        when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("broker down"));
        feed(appointment);

        int reminded = service.sendDueReminders();

        assertThat(reminded).isEqualTo(1); // SMS still fired
        verify(smsService).send(anyString(), anyString());
        assertThat(appointment.getReminderSentAt()).isNotNull();
    }

    @Test
    void perAppointmentFailureNeverStallsTheSweep() {
        Appointment broken = new Appointment();
        broken.setId(UUID.randomUUID());
        // patient null → NPE path caught per-row; second appointment proceeds
        LocalDateTime startsAt = LocalDateTime.now().plusHours(2);
        broken.setAppointmentDate(startsAt.toLocalDate());
        broken.setStartTime(startsAt.toLocalTime());
        feed(broken, appointment);

        int reminded = service.sendDueReminders();

        assertThat(reminded).isEqualTo(1);
        assertThat(appointment.getReminderSentAt()).isNotNull();
    }
}
