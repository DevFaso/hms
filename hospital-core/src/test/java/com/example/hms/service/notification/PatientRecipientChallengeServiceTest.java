package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientRecipientChallenge;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientRecipientChallengeRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class PatientRecipientChallengeServiceTest {
    @Mock PatientRepository patients;
    @Mock PatientRecipientChallengeRepository challenges;
    @Mock SmsService sms;
    @Mock EmailService email;
    @Mock MessageSource messages;
    @Mock Clock clock;
    @Mock org.springframework.transaction.PlatformTransactionManager transactions;
    final UUID actor = UUID.randomUUID();
    Patient patient;
    PatientRecipientChallengeService service;
    AtomicReference<PatientRecipientChallenge> stored;
    String code;

    @BeforeEach void setup() {
        patient = new Patient(); patient.setId(UUID.randomUUID());
        patient.setPhoneNumberPrimary("+22670000000"); patient.setEmail("cdx@example.invalid");
        stored = new AtomicReference<>();
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-11-23T08:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(patients.lockByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        lenient().when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        lenient().when(challenges.findByPatientIdAndChannel(eq(patient.getId()), any()))
            .thenAnswer(call -> Optional.ofNullable(stored.get()));
        lenient().when(challenges.saveAndFlush(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return stored.get(); });
        lenient().when(sms.deliversRealSms()).thenReturn(true);
        lenient().when(email.deliversRealEmail()).thenReturn(true);
        lenient().when(messages.getMessage(eq("recipientVerification.code"), any(), any(Locale.class)))
            .thenAnswer(call -> { code = ((Object[])call.getArgument(1))[0].toString(); return "Code " + code; });
        lenient().when(messages.getMessage(eq("recipientVerification.subject"), isNull(), any(Locale.class))).thenReturn("Contact verification");
        lenient().when(transactions.getTransaction(any())).thenAnswer(call -> new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new PatientRecipientChallengeService(patients, challenges, new BCryptPasswordEncoder(), sms,
            email, messages, clock, new PatientRecipientVerification(), transactions);
    }

    @Test void statusRequiresCurrentRecipientProofForEachChannelAndNeverExposesTheRecipient() {
        var unverified = service.getStatus(patient.getId());
        assertThat(unverified).hasSize(2).allMatch(view -> !view.verified());
        patient.setVerifiedEmailRecipient(patient.getEmail());
        var current = service.getStatus(patient.getId());
        assertThat(current).filteredOn(view -> view.channel() == NotificationChannel.EMAIL)
            .allMatch(PatientRecipientChallengeService.View::verified);
        assertThat(current.toString()).doesNotContain(patient.getEmail(), patient.getPhoneNumberPrimary());
        patient.setEmail("changed@example.invalid");
        assertThat(service.getStatus(patient.getId())).allMatch(view -> !view.verified());
        when(patients.findByIdUnscoped(patient.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getStatus(patient.getId())).isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test void smsChallengeIsHashedBoundToActorAndRecipientConsumedOnce() {
        service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH);
        assertThat(stored.get().getCodeHash()).doesNotContain(code);
        assertThat(patient.getVerifiedSmsRecipient()).isNull();
        assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, UUID.randomUUID(), code)).isInstanceOf(BusinessException.class);
        assertThat(service.confirm(patient.getId(), NotificationChannel.SMS, actor, code).verified()).isTrue();
        assertThat(patient.getVerifiedSmsRecipient()).isEqualTo(patient.getPhoneNumberPrimary());
        assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, actor, code)).isInstanceOf(BusinessException.class);
        verify(sms).send(eq("+22670000000"), eq("Code " + code));
        verifyNoInteractions(email);
    }

    @Test void emailUsesOnlyTheExistingContactAndDoesNotVerifySms() {
        service.request(patient.getId(), NotificationChannel.EMAIL, actor, Locale.FRENCH);
        service.confirm(patient.getId(), NotificationChannel.EMAIL, actor, code);
        assertThat(patient.getVerifiedEmailRecipient()).isEqualTo("cdx@example.invalid");
        assertThat(patient.getVerifiedSmsRecipient()).isNull();
        verify(email).sendWithAttachment(eq(java.util.List.of("cdx@example.invalid")), eq(java.util.List.of()),
            eq(java.util.List.of()), eq("Contact verification"), eq("Code " + code), isNull(), isNull(), isNull());
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        verifyNoInteractions(sms);
    }

    @Test void changedContactCannotUseTheOldCodeOrResurrectPriorProof() {
        service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH);
        patient.setPhoneNumberPrimary("+22671111111");
        assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, actor, code)).isInstanceOf(BusinessException.class);
        patient.setVerifiedSmsRecipient("+22671111111");
        patient.setPhoneNumberPrimary("+22672222222");
        patient.setPhoneNumberPrimary("+22671111111");
        assertThat(patient.getVerifiedSmsRecipient()).isNull();
        patient.setVerifiedEmailRecipient(patient.getEmail());
        patient.setEmail("other@example.invalid"); patient.setEmail("cdx@example.invalid");
        assertThat(patient.getVerifiedEmailRecipient()).isNull();
    }

    @Test void fiveWrongCodesLockTheChallengeEvenIfTheRightCodeThenArrives() {
        service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH);
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, actor, "wrong"))
            .isInstanceOf(BusinessException.class);
        assertThat(stored.get().getAttempts()).isEqualTo(5);
        assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, actor, code)).isInstanceOf(BusinessException.class);
        assertThat(patient.getVerifiedSmsRecipient()).isNull();
    }

    @Test void expiryCooldownAndHourlyCapAreEnforcedWithoutSendingAgain() {
        service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH);
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH)).isInstanceOf(BusinessException.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-23T08:11:00Z"));
        assertThatThrownBy(() -> service.confirm(patient.getId(), NotificationChannel.SMS, actor, code)).isInstanceOf(BusinessException.class);
        stored.get().setRequestCount(5);
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH)).isInstanceOf(BusinessException.class);
        verify(sms, times(1)).send(any(), any());
    }

    @Test void aProviderFailureStillConsumesTheRateBudgetAndInvalidatesTheCode() {
        doThrow(new IllegalStateException("provider detail must never reach client")).when(sms).send(any(), any());
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH))
            .isInstanceOf(BusinessException.class).hasMessageNotContaining("provider detail");
        assertThat(stored.get().getRequestCount()).isEqualTo(1);
        assertThat(stored.get().getCodeHash()).isNull();
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH)).isInstanceOf(BusinessException.class);
    }

    @Test void anEmailProviderFailureInvalidatesTheCodeWithoutQueueingOrRetryingIt() {
        doThrow(new IllegalStateException("provider detail must never reach client")).when(email)
            .sendWithAttachment(any(), any(), any(), any(), any(), isNull(), isNull(), isNull());
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.EMAIL, actor, Locale.FRENCH))
            .isInstanceOf(BusinessException.class).hasMessageNotContaining("provider detail");
        assertThat(stored.get().getRequestCount()).isEqualTo(1);
        assertThat(stored.get().getCodeHash()).isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
    }

    @Test void aLateFailureNeverInvalidatesASupersedingChallenge() {
        doAnswer(call -> {
            stored.get().setCodeHash("cdx-later-challenge");
            throw new IllegalStateException("cdx-first-send-failed");
        }).when(sms).send(any(), any());
        assertThatThrownBy(() -> service.request(patient.getId(), NotificationChannel.SMS, actor, Locale.FRENCH))
            .isInstanceOf(BusinessException.class);
        assertThat(stored.get().getCodeHash()).isEqualTo("cdx-later-challenge");
    }
}
