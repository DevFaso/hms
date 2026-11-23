package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D2201 / D2202 / D2206: one consent gate for every patient SMS and e-mail sent inline. */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientMessageGate: the patient's channel choice, and the trace of a refusal")
class PatientMessageGateTest {

    @Mock private PatientChannelConsent consent;
    @Mock private PatientNotificationLedger ledger;
    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private PatientRecipientCurrentCheck currentCheck;

    private PatientMessageGate gate;
    private Patient patient;
    private UUID userId;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    private static final PatientMessageGate.AppointmentMail MAIL = new PatientMessageGate.AppointmentMail(
        "Seydou Traoré", "CHU", "Dr Ouédraogo", "18 nov. 2026", "09:00 - 09:30", "chu@example.test", "+22625000000",
        "https://x/r", "https://x/c", Locale.FRENCH);

    @BeforeEach
    void setUp() {
        gate = new PatientMessageGate(consent, ledger, smsService, emailService, new PatientRecipientVerification(), currentCheck);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setVerifiedSmsRecipient("+22670707070");
        patient.setEmail("seydou@example.test");
        patient.setVerifiedEmailRecipient("seydou@example.test");
        User user = new User();
        userId = UUID.randomUUID();
        user.setId(userId);
        user.setEmail("seydou.portal@example.test");
        patient.setUser(user);
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        lenient().when(consent.allows(any(), any(), any())).thenReturn(true);
    }

    private PatientMessageGate.Notice notice(PatientNotificationEvent event) {
        return new PatientMessageGate.Notice(event, hospitalId, subjectId);
    }

    private void optOut(NotificationType type, NotificationChannel channel) {
        when(consent.allows(userId, type, channel)).thenReturn(false);
    }

    @Test
    void deferredSmsRechecksCommittedRecipientOwnershipInsteadOfTheCachedPatient() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(gate.smsAfterCommit(patient, notice(PatientNotificationEvent.PHARMACY_READY), "ready")).isTrue();
            verify(smsService, never()).send(any(), any());
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(
                org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(currentCheck).allows(patient.getId(), NotificationChannel.SMS, patient.getPhoneNumberPrimary());
            verify(smsService, never()).send(any(), any());
            verify(ledger).recordUnverified(PatientNotificationEvent.PHARMACY_READY, hospitalId, patient.getId(), subjectId, NotificationChannel.SMS);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("D2201: an appointment e-mail goes out when the patient allows it, as its event's template")
    void appointmentMailAllowed() {
        assertThat(gate.appointmentEmail(patient, notice(PatientNotificationEvent.APPOINTMENT_CANCELLED), MAIL))
            .isTrue();

        verify(emailService).sendAppointmentCancelledEmail("seydou@example.test", "Seydou Traoré", "CHU",
            "Dr Ouédraogo", "18 nov. 2026", "09:00 - 09:30", "chu@example.test", "+22625000000", Locale.FRENCH);
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("D2201: every appointment e-mail stops when appointment e-mails are off, each leaving a trace")
    void appointmentMailOptedOut() {
        optOut(NotificationType.APPOINTMENT_REMINDER, NotificationChannel.EMAIL);

        for (PatientNotificationEvent event : List.of(PatientNotificationEvent.APPOINTMENT_CONFIRMATION,
            PatientNotificationEvent.APPOINTMENT_RESCHEDULED, PatientNotificationEvent.APPOINTMENT_CANCELLED,
            PatientNotificationEvent.APPOINTMENT_COMPLETED, PatientNotificationEvent.APPOINTMENT_NO_SHOW)) {
            assertThat(gate.appointmentEmail(patient, notice(event), MAIL)).as(event.name()).isFalse();
            verify(ledger).recordOptOut(event, hospitalId, patient.getId(), subjectId, NotificationChannel.EMAIL);
        }
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("an appointment e-mail to a patient with no address is not a refusal: no trace")
    void noAddressNoTrace() {
        patient.setEmail(" ");
        patient.setVerifiedEmailRecipient(" ");
        assertThat(gate.appointmentEmail(patient, notice(PatientNotificationEvent.APPOINTMENT_CONFIRMATION), MAIL))
            .isFalse();
        verifyNoInteractions(emailService, ledger, consent);
    }

    @Test
    @DisplayName("a non-appointment event cannot be sent as an appointment e-mail")
    void wrongEventRefused() {
        PatientMessageGate.Notice invoice = notice(PatientNotificationEvent.INVOICE);
        assertThatThrownBy(() -> gate.appointmentEmail(patient, invoice, MAIL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D2201: the visit-summary e-mail follows DISCHARGE_SUMMARY, not the appointment choice")
    void visitSummaryFollowsItsOwnPreference() {
        optOut(NotificationType.DISCHARGE_SUMMARY, NotificationChannel.EMAIL);

        assertThat(gate.htmlEmail(patient, notice(PatientNotificationEvent.VISIT_SUMMARY), "seydou@example.test",
            "s", "<p>b</p>")).isFalse();

        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
        verify(ledger).recordOptOut(PatientNotificationEvent.VISIT_SUMMARY, hospitalId, patient.getId(), subjectId,
            NotificationChannel.EMAIL);
        assertThat(gate.appointmentEmail(patient, notice(PatientNotificationEvent.APPOINTMENT_CONFIRMATION), MAIL))
            .isTrue();
    }

    @Test
    @DisplayName("D2201: the in-app bell follows the patient's IN_APP choice")
    void inAppChoice() {
        optOut(NotificationType.DISCHARGE_SUMMARY, NotificationChannel.IN_APP);
        assertThat(gate.allowsInApp(patient, NotificationType.DISCHARGE_SUMMARY)).isFalse();
        assertThat(gate.allowsInApp(patient, NotificationType.APPOINTMENT_REMINDER)).isTrue();
        patient.setUser(null);
        assertThat(gate.allowsInApp(patient, NotificationType.APPOINTMENT_REMINDER)).as("no portal, no bell")
            .isFalse();
    }

    @Test
    @DisplayName("D2202: an SMS is sent now when allowed, and not when the patient turned SMS off")
    void smsNow() {
        assertThat(gate.smsNow(patient, notice(PatientNotificationEvent.RECALL_DUE), "body")).isTrue();
        verify(smsService).send("+22670707070", "body");

        optOut(NotificationType.APPOINTMENT_REMINDER, NotificationChannel.SMS);
        assertThat(gate.smsNow(patient, notice(PatientNotificationEvent.WAITLIST_OFFER), "other")).isFalse();
        verify(smsService, never()).send(anyString(), eq("other"));
        verify(ledger).recordOptOut(PatientNotificationEvent.WAITLIST_OFFER, hospitalId, patient.getId(), subjectId,
            NotificationChannel.SMS);
    }

    @Test
    @DisplayName("the logging mock transport is never handed patient text, and that is not an opt-out")
    void mockTransportNeverCarriesPatientText() {
        when(smsService.deliversRealSms()).thenReturn(false);
        assertThat(gate.smsNow(patient, notice(PatientNotificationEvent.RECALL_DUE), "body")).isFalse();
        assertThat(gate.smsAfterCommit(patient, notice(PatientNotificationEvent.PHARMACY_READY), "body")).isFalse();
        verify(smsService, never()).send(any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("a patient with no portal account cannot opt out yet (D2203) and is allowed")
    void noPortalAccountAllowed() {
        patient.setUser(null);
        when(consent.allows(null, NotificationType.MEDICATION_REFILL, NotificationChannel.SMS)).thenReturn(true);
        assertThat(gate.smsAfterCommit(patient, notice(PatientNotificationEvent.PHARMACY_DISPENSED), "b")).isTrue();
        verify(smsService).send("+22670707070", "b");
    }

    @Test
    @DisplayName("an SMS failure after commit is swallowed, never the caller's problem")
    void afterCommitFailureSwallowed() {
        doThrow(new IllegalStateException("sms.provider.unreachable")).when(smsService).send(any(), any());
        assertThat(gate.smsAfterCommit(patient, notice(PatientNotificationEvent.PHARMACY_DISPENSED), "b")).isTrue();
    }

    @Test
    void attachmentMailRechecksCurrentProofAfterThePatientSnapshotWasLoaded() {
        PatientMessageGate.OutgoingMail mail = new PatientMessageGate.OutgoingMail(
            List.of(patient.getEmail()), null, null, "cdx-subject", "cdx-body");
        assertThat(gate.emailWithAttachment(patient, notice(PatientNotificationEvent.INVOICE), mail,
            new byte[] {1}, "cdx.pdf", "application/pdf")).isFalse();
        verifyNoInteractions(emailService);
        verify(ledger).recordUnverified(PatientNotificationEvent.INVOICE, hospitalId, patient.getId(), subjectId, NotificationChannel.EMAIL);
        when(currentCheck.allows(patient.getId(), NotificationChannel.EMAIL, patient.getEmail())).thenReturn(true);
        assertThat(gate.emailWithAttachment(patient, notice(PatientNotificationEvent.INVOICE), mail,
            new byte[] {1}, "cdx.pdf", "application/pdf")).isTrue();
        verify(emailService).sendWithAttachment(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("D2201: an invoice mail that includes the patient's address follows BILLING; third parties do not")
    void invoiceRecipients() {
        optOut(NotificationType.BILLING, NotificationChannel.EMAIL);
        PatientMessageGate.OutgoingMail toInsurer = new PatientMessageGate.OutgoingMail(
            List.of("claims@insurer.test"), null, null, "s", "b");
        PatientMessageGate.OutgoingMail bccPortal = new PatientMessageGate.OutgoingMail(
            List.of("claims@insurer.test"), List.of(), List.of(" SEYDOU.portal@example.test"), "s", "b");

        assertThat(gate.emailWithAttachment(patient, notice(PatientNotificationEvent.INVOICE), toInsurer, null,
            "f.pdf", "application/pdf")).isTrue();
        assertThat(gate.emailWithAttachment(patient, notice(PatientNotificationEvent.INVOICE), bccPortal, null,
            "f.pdf", "application/pdf")).isFalse();

        verify(emailService).sendWithAttachment(List.of("claims@insurer.test"), List.of(), List.of(), "s", "b", null,
            "f.pdf", "application/pdf");
        verify(ledger).recordOptOut(PatientNotificationEvent.INVOICE, hospitalId, patient.getId(), subjectId,
            NotificationChannel.EMAIL);
        assertThat(toInsurer.toString()).doesNotContain("insurer");
    }
}
