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
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private SmsService smsService;

    private static final com.example.hms.service.i18n.NotificationText RECALL =
        com.example.hms.service.i18n.NotificationText.of("sms.recall.due", "CHU Yalgado");
    private static final String RECALL_FR =
        RECALL.render(com.example.hms.i18n.TestMessageSources.bundles(), java.util.Locale.FRENCH);

    private PatientOutreachNotifier notifier;

    private Patient patient;
    private User portalUser;

    @BeforeEach
    void setUp() {
        notifier = new PatientOutreachNotifier(preferenceRepository, notificationService, smsService,
            com.example.hms.i18n.TestMessageSources.bundles());

        portalUser = new User();
        portalUser.setId(UUID.randomUUID());
        portalUser.setUsername("awa.traore");

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setUser(portalUser);

        lenient().when(notificationService.created(anyString(), anyString(), anyString()))
            .thenReturn(new Notification());
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(preferenceRepository.findByUser_IdAndNotificationType(
            portalUser.getId(), NotificationType.APPOINTMENT_REMINDER))
            .thenReturn(List.of());
    }

    @Test
    void dispatchesOverBothChannels() {
        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH);

        assertThat(dispatched).isTrue();
        verify(notificationService).created(
            eq(RECALL_FR), eq("awa.traore"), eq("APPOINTMENT_REMINDER"));
        verify(smsService).send("+22670707070", RECALL_FR);
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        // The mock transport logs bodies — it must never carry PHI.
        when(smsService.deliversRealSms()).thenReturn(false);

        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH);

        verify(smsService, never()).send(anyString(), anyString());
        assertThat(dispatched).isTrue(); // in-app still went out
    }

    @Test
    void aPatientWithoutAPortalAccountStillGetsSms() {
        patient.setUser(null);

        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH);

        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(smsService).send("+22670707070", RECALL_FR);
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

        notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH);

        verify(smsService, never()).send(anyString(), anyString());
        verify(notificationService).created(anyString(), anyString(), anyString());
    }

    @Test
    void longBodiesAreCappedForSms() {
        String longMessage = "x".repeat(500);

        notifier.notifyPatient(patient,
            com.example.hms.service.i18n.NotificationText.of("sms.recall.due", longMessage), java.util.Locale.FRENCH);

        verify(smsService).send(eq("+22670707070"), argThat(body -> body.length() == 320));
    }

    @Test
    void nothingToSayMeansNothingSent() {
        assertThat(notifier.notifyPatient(patient, null, java.util.Locale.FRENCH)).isFalse();
        assertThat(notifier.notifyPatient(null, RECALL, java.util.Locale.FRENCH)).isFalse();
        verify(smsService, never()).send(anyString(), anyString());
    }
}
