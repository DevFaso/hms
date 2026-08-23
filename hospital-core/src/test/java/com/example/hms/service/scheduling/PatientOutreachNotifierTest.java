package com.example.hms.service.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.Notification;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.SmsService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Shared outreach dispatch (P3 #22) — the channel contract inherited from
 * AppointmentReminderService: in-app needs a portal account, SMS needs a
 * real transport, and both honour stored preferences.
 */
@ExtendWith(MockitoExtension.class)
class PatientOutreachNotifierTest {

    @Mock private NotificationPreferenceRepository preferenceRepository;
    @Mock private NotificationService notificationService;
    @Mock private SmsService smsService;

    private PatientOutreachNotifier notifier;

    private Patient patient;
    private User portalUser;

    @BeforeEach
    void setUp() {
        notifier = new PatientOutreachNotifier(preferenceRepository, notificationService, smsService);

        portalUser = new User();
        portalUser.setId(UUID.randomUUID());
        portalUser.setUsername("awa.traore");

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setUser(portalUser);

        lenient().when(notificationService.createNotification(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(preferenceRepository.findByUser_IdAndNotificationType(
            portalUser.getId(), NotificationType.APPOINTMENT_REMINDER))
            .thenReturn(List.of());
    }

    @Test
    void dispatchesOverBothChannels() {
        boolean dispatched = notifier.notifyPatient(patient, "A slot opened up.");

        assertThat(dispatched).isTrue();
        verify(notificationService).createNotification(
            eq("A slot opened up."), eq("awa.traore"), eq("APPOINTMENT_REMINDER"));
        verify(smsService).send("+22670707070", "A slot opened up.");
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        // The mock transport logs bodies — it must never carry PHI.
        when(smsService.deliversRealSms()).thenReturn(false);

        boolean dispatched = notifier.notifyPatient(patient, "A slot opened up.");

        verify(smsService, never()).send(anyString(), anyString());
        assertThat(dispatched).isTrue(); // in-app still went out
    }

    @Test
    void aPatientWithoutAPortalAccountStillGetsSms() {
        patient.setUser(null);

        boolean dispatched = notifier.notifyPatient(patient, "A slot opened up.");

        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
        verify(smsService).send("+22670707070", "A slot opened up.");
        assertThat(dispatched).isTrue();
    }

    @Test
    void anExplicitOptOutSilencesTheChannel() {
        NotificationPreference smsOff = new NotificationPreference();
        smsOff.setChannel(NotificationChannel.SMS);
        smsOff.setEnabled(false);
        when(preferenceRepository.findByUser_IdAndNotificationType(
            portalUser.getId(), NotificationType.APPOINTMENT_REMINDER))
            .thenReturn(List.of(smsOff));

        notifier.notifyPatient(patient, "A slot opened up.");

        verify(smsService, never()).send(anyString(), anyString());
        verify(notificationService).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    void longBodiesAreCappedForSms() {
        String longMessage = "x".repeat(500);

        notifier.notifyPatient(patient, longMessage);

        verify(smsService).send(eq("+22670707070"), argThat(body -> body.length() == 320));
    }

    @Test
    void nothingToSayMeansNothingSent() {
        assertThat(notifier.notifyPatient(patient, "  ")).isFalse();
        assertThat(notifier.notifyPatient(null, "hello")).isFalse();
        verify(smsService, never()).send(anyString(), anyString());
    }
}
