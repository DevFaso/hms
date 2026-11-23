package com.example.hms.service.notification;

import com.example.hms.enums.*;
import com.example.hms.model.Patient;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PatientRecipientVerificationTest {
    @Test void everyPatientEventIsBlockedUntilItsExactRecipientHasProof() {
        Patient patient = new Patient(); patient.setId(UUID.randomUUID());
        patient.setEmail("cdx@example.invalid"); patient.setPhoneNumberPrimary("+22670000000");
        PatientChannelConsent consent = mock(PatientChannelConsent.class);
        when(consent.allows(any(), any(), any())).thenReturn(true);
        SmsService sms = mock(SmsService.class); when(sms.deliversRealSms()).thenReturn(true);
        EmailService email = mock(EmailService.class);
        PatientMessageGate gate = new PatientMessageGate(consent, mock(PatientNotificationLedger.class), sms, email, new PatientRecipientVerification(), org.mockito.Mockito.mock(com.example.hms.service.notification.PatientRecipientCurrentCheck.class));
        for (PatientNotificationEvent event : PatientNotificationEvent.values()) {
            var notice = new PatientMessageGate.Notice(event, UUID.randomUUID(), UUID.randomUUID());
            assertThat(gate.smsNow(patient, notice, "clinical text")).as(event.name()).isFalse();
            assertThat(gate.htmlEmail(patient, notice, patient.getEmail(), "subject", "clinical text")).as(event.name()).isFalse();
        }
        verify(sms, never()).send(any(), any()); verifyNoInteractions(email);
        patient.setVerifiedSmsRecipient(patient.getPhoneNumberPrimary());
        assertThat(gate.smsNow(patient, new PatientMessageGate.Notice(PatientNotificationEvent.PHARMACY_READY,
            UUID.randomUUID(), UUID.randomUUID()), "ready")).isTrue();
        patient.setPhoneNumberPrimary("+22671111111");
        assertThat(gate.smsNow(patient, new PatientMessageGate.Notice(PatientNotificationEvent.PHARMACY_READY,
            UUID.randomUUID(), UUID.randomUUID()), "ready")).isFalse();
        verify(sms, times(1)).send(any(), any());
    }

    @Test void anAccountEmailDoesNotBorrowProofFromTheChartEmail() {
        Patient patient = new Patient(); patient.setEmail("verified@example.invalid");
        patient.setVerifiedEmailRecipient(patient.getEmail());
        var verification = new PatientRecipientVerification();
        assertThat(verification.allows(patient, NotificationChannel.EMAIL, " VERIFIED@example.invalid ")).isTrue();
        assertThat(verification.allows(patient, NotificationChannel.EMAIL, "account@example.invalid")).isFalse();
        assertThat(verification.allows(patient, NotificationChannel.IN_APP, patient.getEmail())).isFalse();
    }
}
