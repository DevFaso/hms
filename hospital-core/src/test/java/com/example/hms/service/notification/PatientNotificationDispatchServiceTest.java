package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import com.example.hms.service.integration.SmsRecipientNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Patient notification sweep: bounded retry, no double send")
class PatientNotificationDispatchServiceTest {

    private static final String PHONE = "+22670707070";
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    @Mock private PatientNotificationDeliveryRepository repository;
    @Mock private PatientNotificationRenderer renderer;
    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private PlatformTransactionManager transactionManager;

    private PatientNotificationProperties properties;
    private PatientNotificationDispatchService service;
    private PatientNotificationDelivery row;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        properties = new PatientNotificationProperties();
        service = new PatientNotificationDispatchService(repository, renderer, smsService, emailService, properties,
            Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
        row = PatientNotificationDelivery.builder()
            .eventType(PatientNotificationEvent.LAB_RESULT_AVAILABLE)
            .channel(NotificationChannel.SMS)
            .status(NotificationDeliveryStatus.PENDING)
            .attempts(1) // the claim counted it
            .nextAttemptAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
            .build();
        row.setId(UUID.randomUUID());
        lenient().when(repository.findDispatchableIds(any(), anyInt(), any(), any())).thenReturn(List.of(row.getId()));
        lenient().when(repository.claim(eq(row.getId()), any(), anyInt(), any(), any())).thenReturn(1);
        lenient().when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        lenient().when(renderer.prepare(row.getId()))
            .thenReturn(new PatientNotificationRenderer.Prepared(NotificationChannel.SMS, PHONE, null, "texte"));
        lenient().when(smsService.deliversRealSms()).thenReturn(true);
        // The conditional outcome writes, applied to the row as the database would while the claim holds.
        lenient().when(repository.markSent(eq(row.getId()), any(), any(), any(), any())).thenAnswer(inv -> {
            row.setStatus(inv.getArgument(3));
            row.setSentAt(inv.getArgument(4));
            row.setErrorCode(null);
            return 1;
        });
        lenient().when(repository.markFailure(eq(row.getId()), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                row.setStatus(inv.getArgument(3));
                row.setErrorCode(inv.getArgument(4));
                row.setNextAttemptAt(inv.getArgument(5));
                return 1;
            });
    }

    @Test
    @DisplayName("an accepted send is SENT")
    void sent() {
        assertThat(service.dispatchPending()).isEqualTo(1);

        verify(smsService).send(PHONE, "texte");
        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("a lost claim sends nothing: another instance has it")
    void lostClaimSendsNothing() {
        when(repository.claim(eq(row.getId()), any(), anyInt(), any(), any())).thenReturn(0);

        assertThat(service.dispatchPending()).isZero();
        verify(smsService, never()).send(anyString(), anyString());
        verify(renderer, never()).prepare(any());
    }

    @Test
    @DisplayName("a transient failure is RETRYING with the doubling backoff, and the code never quotes the number")
    void transientFailureRetries() {
        doThrow(new IllegalStateException("sms.provider.unreachable")).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRYING);
        assertThat(row.getErrorCode()).isEqualTo("PROVIDER_UNREACHABLE").doesNotContain("7070");
        assertThat(row.getNextAttemptAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(60));
    }

    @Test
    @DisplayName("a transient failure on the last attempt is FAILED: the retry is bounded")
    void retryIsBounded() {
        row.setAttempts(properties.getMaxAttempts());
        doThrow(new IllegalStateException("sms.provider.unreachable")).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row.getErrorCode()).isEqualTo("PROVIDER_UNREACHABLE");
    }

    @Test
    @DisplayName("an unknown outcome is FAILED at once: the provider may have charged and delivered it")
    void unknownOutcomeIsNotResent() {
        doThrow(new IllegalStateException("sms.provider.unknownOutcome")).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row.getErrorCode()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    @DisplayName("an SMS failure nobody classified is an unknown outcome: never resent automatically")
    void unclassifiedSmsFailureIsUnknown() {
        doThrow(new IllegalStateException("IKODDI exploded")).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row.getErrorCode()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    @DisplayName("an outcome is written with the claim token: a sweep that lost its claim never overwrites")
    void outcomeIsWrittenUnderTheClaim() {
        when(repository.markSent(eq(row.getId()), any(), any(), any(), any())).thenReturn(0);
        org.mockito.ArgumentCaptor<LocalDateTime> claimedAt = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);

        service.dispatchPending();

        verify(repository).claim(eq(row.getId()), any(), anyInt(), claimedAt.capture(), any());
        verify(repository).markSent(eq(row.getId()), eq(claimedAt.getValue()), any(), any(), any());
        assertThat(row.getStatus()).as("the newer attempt's state is untouched").isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("a refused message is FAILED at once")
    void rejectedIsPermanent() {
        doThrow(new IllegalStateException("sms.provider.rejected")).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row.getErrorCode()).isEqualTo("PROVIDER_REJECTED");
    }

    @Test
    @DisplayName("a number not on the dev allowlist is SKIPPED, not FAILED")
    void allowlistRefusalIsASkip() {
        doThrow(new SmsRecipientNotAllowedException()).when(smsService).send(anyString(), anyString());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
        assertThat(row.getErrorCode()).isEqualTo("RECIPIENT_NOT_ALLOWED");
    }

    @Test
    @DisplayName("a mock SMS transport is never handed patient text: the row waits for a real one")
    void mockTransportIsNeverUsed() {
        when(smsService.deliversRealSms()).thenReturn(false);

        service.dispatchPending();

        verify(smsService, never()).send(anyString(), anyString());
        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRYING);
        assertThat(row.getErrorCode()).isEqualTo("TRANSPORT_UNAVAILABLE");
    }

    @Test
    @DisplayName("an email goes through the synchronous transport, and an SMTP failure is retried")
    void emailRetries() {
        row.setChannel(NotificationChannel.EMAIL);
        row.setChannel(NotificationChannel.EMAIL);
        when(renderer.prepare(row.getId())).thenReturn(new PatientNotificationRenderer.Prepared(
            NotificationChannel.EMAIL, "awa@example.com", "Sujet", "<p>x</p>"));
        doThrow(new MailSendException("smtp down awa@example.com")).when(emailService)
            .sendWithAttachment(any(), any(), any(), anyString(), anyString(), any(), any(), any());

        service.dispatchPending();

        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRYING);
        assertThat(row.getErrorCode()).isEqualTo("MAIL_SEND_FAILED");
    }

    @Test
    @DisplayName("a row the renderer skipped is not sent")
    void skippedRowIsNotSent() {
        when(renderer.prepare(row.getId())).thenReturn(null);

        assertThat(service.dispatchPending()).isZero();
        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("the switch off sends nothing")
    void disabled() {
        properties.setEnabled(false);

        assertThat(service.dispatchPending()).isZero();
        verify(repository, never()).findDispatchableIds(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("the backoff doubles and is capped")
    void backoff() {
        assertThat(service.backoffSeconds(1)).isEqualTo(60);
        assertThat(service.backoffSeconds(2)).isEqualTo(120);
        assertThat(service.backoffSeconds(3)).isEqualTo(240);
        assertThat(service.backoffSeconds(30)).isEqualTo(3_600);
    }

    @Test
    @DisplayName("an unconfigured transport maps to a retryable code")
    void transportUnavailableCode() {
        assertThat(PatientNotificationErrorCode.of(new NotificationTransportUnavailableException("x"),
            NotificationChannel.SMS))
            .isEqualTo(PatientNotificationErrorCode.TRANSPORT_UNAVAILABLE)
            .extracting(PatientNotificationErrorCode::retryable).isEqualTo(true);
    }
}
