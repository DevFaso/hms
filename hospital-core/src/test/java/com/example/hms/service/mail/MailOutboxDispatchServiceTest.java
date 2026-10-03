package com.example.hms.service.mail;

import com.example.hms.enums.platform.MailOutboxStatus;
import com.example.hms.model.platform.MailOutboxMessage;
import com.example.hms.repository.platform.MailOutboxRepository;
import com.example.hms.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbox sweep's contracts (V173): a row is claimed by conditional update
 * before any SMTP work, so a lost claim sends nothing; a send is SENT and
 * loses its subject and body; a failure backs off (doubling, capped) until the
 * ceiling, then is FAILED and loses its body too; a message the library
 * cannot build fails at once; the error column holds a class name, never a
 * message that could quote the address.
 */
@ExtendWith(MockitoExtension.class)
class MailOutboxDispatchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 9, 0);

    @Mock private MailOutboxRepository repository;
    @Mock private EmailService emailService;

    private MailOutboxProperties properties;
    private MailOutboxDispatchService service;
    private MailOutboxMessage message;
    private UUID id;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        properties = new MailOutboxProperties();
        properties.setMaxAttempts(3);
        properties.setInitialBackoffSeconds(30);
        properties.setMaxBackoffSeconds(100);
        // A bare mock manager: TransactionTemplate runs each callback inline.
        service = new MailOutboxDispatchService(repository, emailService, properties, clock,
            mock(PlatformTransactionManager.class));

        id = UUID.randomUUID();
        message = MailOutboxMessage.builder()
            .recipients("awa@example.com\nbob@example.com")
            .ccRecipients("cc@example.com")
            .subject("Your code")
            .htmlBody("<p>123456</p>")
            .nextAttemptAt(NOW)
            .build();
        message.setId(id);
    }

    private void oneDueRow(int claimResult) {
        when(repository.findDispatchableIds(eq(MailOutboxStatus.PENDING), eq(3), eq(NOW), any()))
            .thenReturn(List.of(id));
        when(repository.claim(id, MailOutboxStatus.PENDING, 3, NOW, NOW.plusSeconds(300)))
            .thenReturn(claimResult);
    }

    private void claimedAt(int attemptsAfterClaim) {
        message.setAttempts(attemptsAfterClaim);
        when(repository.findById(id)).thenReturn(Optional.of(message));
    }

    @Test
    @DisplayName("a lost claim sends nothing: another instance has the row")
    void lostClaimSendsNothing() {
        oneDueRow(0);

        assertThat(service.dispatchPending()).isZero();

        verifyNoInteractions(emailService);
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("a sent row is SENT, stamped, and keeps no subject or body")
    void sentRowDropsItsBody() {
        oneDueRow(1);
        claimedAt(1);

        assertThat(service.dispatchPending()).isEqualTo(1);

        verify(emailService).sendWithAttachment(List.of("awa@example.com", "bob@example.com"),
            List.of("cc@example.com"), List.of(), "Your code", "<p>123456</p>", null, null, null);
        assertThat(message.getStatus()).isEqualTo(MailOutboxStatus.SENT);
        assertThat(message.getSentAt()).isEqualTo(NOW);
        assertThat(message.getHtmlBody()).isNull();
        assertThat(message.getSubject()).isNull();
        assertThat(message.getLastError()).isNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("a transport failure below the ceiling backs off and keeps the body for the retry")
    void transientFailureBacksOff() {
        oneDueRow(1);
        claimedAt(2);
        doThrow(new MailSendException("550 rejected <awa@example.com>"))
            .when(emailService).sendWithAttachment(anyList(), anyList(), anyList(), any(), any(), any(), any(), any());

        assertThat(service.dispatchPending()).isZero();

        assertThat(message.getStatus()).isEqualTo(MailOutboxStatus.PENDING);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(message.getHtmlBody()).isEqualTo("<p>123456</p>");
        assertThat(message.getLastError())
            .as("the class only: the message quotes the address")
            .isEqualTo("MailSendException");
    }

    @Test
    @DisplayName("the last attempt failing is terminal FAILED, and the body goes")
    void lastAttemptFailureIsTerminal() {
        oneDueRow(1);
        claimedAt(3);
        doThrow(new MailSendException("timeout"))
            .when(emailService).sendWithAttachment(anyList(), anyList(), anyList(), any(), any(), any(), any(), any());

        service.dispatchPending();

        assertThat(message.getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(message.getHtmlBody()).isNull();
        assertThat(message.getSubject()).isNull();
        assertThat(message.getLastError()).isEqualTo("MailSendException");
    }

    @Test
    @DisplayName("a message the library cannot build fails at once: retrying the same bytes cannot help")
    void unbuildableMessageFailsAtOnce() {
        oneDueRow(1);
        claimedAt(1);
        doThrow(new MailPreparationException("bad MIME"))
            .when(emailService).sendWithAttachment(anyList(), anyList(), anyList(), any(), any(), any(), any(), any());

        service.dispatchPending();

        assertThat(message.getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(message.getHtmlBody()).isNull();
    }

    @Test
    @DisplayName("a pending row with no body is closed, never sent")
    void rowWithoutBodyIsClosed() {
        oneDueRow(1);
        message.setHtmlBody(null);
        claimedAt(1);

        service.dispatchPending();

        verifyNoInteractions(emailService);
        assertThat(message.getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(message.getLastError()).isEqualTo(MailOutboxDispatchService.MISSING_BODY);
    }

    @Test
    @DisplayName("every sweep closes rows that used their last claim without an outcome")
    void exhaustedRowsAreClosed() {
        when(repository.findDispatchableIds(any(), anyInt(), any(), any())).thenReturn(List.of());

        service.dispatchPending();

        verify(repository).closeExhausted(MailOutboxStatus.PENDING, MailOutboxStatus.FAILED, 3, NOW,
            MailOutboxDispatchService.EXHAUSTED);
        verify(repository).deleteTerminalBefore(List.of(MailOutboxStatus.SENT, MailOutboxStatus.FAILED),
            NOW.minusDays(30));
    }

    @Test
    @DisplayName("switched off, the sweep touches nothing")
    void disabledSweepIsANoOp() {
        properties.setEnabled(false);

        assertThat(service.dispatchPending()).isZero();

        verifyNoInteractions(repository, emailService);
    }

    @Test
    @DisplayName("the backoff doubles from the initial wait and stops at the ceiling")
    void backoffDoublesAndCaps() {
        assertThat(service.backoffSeconds(1)).isEqualTo(30);
        assertThat(service.backoffSeconds(2)).isEqualTo(60);
        assertThat(service.backoffSeconds(3)).isEqualTo(100);
        assertThat(service.backoffSeconds(60)).isEqualTo(100);
    }
}
