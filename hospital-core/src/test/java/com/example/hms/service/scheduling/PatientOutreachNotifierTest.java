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
import com.example.hms.service.SmsService;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.service.notification.PatientChannelConsent;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.service.notification.PatientNotificationLedger;
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
    @Mock private com.example.hms.service.EmailService emailService;
    @Mock private PatientNotificationLedger ledger;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID RECALL_ID = UUID.randomUUID();
    private static final PatientMessageGate.Notice NOTICE =
        new PatientMessageGate.Notice(PatientNotificationEvent.RECALL_DUE, HOSPITAL_ID, RECALL_ID);

    private static final com.example.hms.service.i18n.NotificationText RECALL =
        com.example.hms.service.i18n.NotificationText.of("sms.recall.due", "CHU Yalgado");
    private static final String RECALL_FR =
        RECALL.render(com.example.hms.i18n.TestMessageSources.bundles(), java.util.Locale.FRENCH);

    private PatientOutreachNotifier notifier;

    private Patient patient;
    private User portalUser;

    @BeforeEach
    void setUp() {
        notifier = new PatientOutreachNotifier(notificationService,
            new PatientMessageGate(new PatientChannelConsent(preferenceRepository), ledger, smsService, emailService, new com.example.hms.service.notification.PatientRecipientVerification(), org.mockito.Mockito.mock(com.example.hms.service.notification.PatientRecipientCurrentCheck.class)),
            com.example.hms.i18n.TestMessageSources.bundles());

        portalUser = new User();
        portalUser.setId(UUID.randomUUID());
        portalUser.setUsername("awa.traore");

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setVerifiedSmsRecipient("+22670707070");
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
        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH, NOTICE);

        assertThat(dispatched).isTrue();
        verify(notificationService).created(RECALL_FR, "awa.traore", "APPOINTMENT_REMINDER");
        verify(smsService).send("+22670707070", RECALL_FR);
    }

    @Test
    void smsStaysOffWhileTransportIsMock() {
        // The mock transport logs bodies — it must never carry PHI.
        when(smsService.deliversRealSms()).thenReturn(false);

        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH, NOTICE);

        verify(smsService, never()).send(anyString(), anyString());
        assertThat(dispatched).isTrue(); // in-app still went out
    }

    @Test
    void aPatientWithoutAPortalAccountStillGetsSms() {
        patient.setUser(null);

        boolean dispatched = notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH, NOTICE);

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

        notifier.notifyPatient(patient, RECALL, java.util.Locale.FRENCH, NOTICE);

        verify(smsService, never()).send(anyString(), anyString());
        verify(notificationService).created(anyString(), anyString(), anyString());
        // QA D2206: the refusal is visible to staff, without the message or the number.
        verify(ledger).recordOptOut(PatientNotificationEvent.RECALL_DUE, HOSPITAL_ID, patient.getId(), RECALL_ID,
            NotificationChannel.SMS);
    }

    @Test
    void longBodiesAreCappedForSms() {
        String longMessage = "x".repeat(500);

        notifier.notifyPatient(patient,
            com.example.hms.service.i18n.NotificationText.of("sms.recall.due", longMessage), java.util.Locale.FRENCH, NOTICE);

        verify(smsService).send(eq("+22670707070"), argThat(body -> body.length() == 320));
    }

    @Test
    void nothingToSayMeansNothingSent() {
        assertThat(notifier.notifyPatient(patient, null, java.util.Locale.FRENCH, NOTICE)).isFalse();
        assertThat(notifier.notifyPatient(null, RECALL, java.util.Locale.FRENCH, NOTICE)).isFalse();
        verify(smsService, never()).send(anyString(), anyString());
    }
}
