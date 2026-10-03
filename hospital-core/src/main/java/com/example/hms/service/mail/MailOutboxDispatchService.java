package com.example.hms.service.mail;

import com.example.hms.enums.platform.MailOutboxStatus;
import com.example.hms.model.platform.MailOutboxMessage;
import com.example.hms.repository.platform.MailOutboxRepository;
import com.example.hms.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The mail outbox sweep, claim-then-send (the webhook outbox's mechanics):
 *
 * <ol>
 *   <li>a short transaction lists due ids;</li>
 *   <li>each row is claimed by a conditional update in its own short
 *       transaction, so a concurrent sweep on another instance gets 0 rows
 *       and skips it: nothing is sent twice by two sweeps;</li>
 *   <li>the message is read (decrypted) in a third short transaction;</li>
 *   <li>SMTP runs with NO transaction open: a stalled mail host holds this
 *       sweep's thread, never a database connection;</li>
 *   <li>the outcome is recorded in a last short transaction.</li>
 * </ol>
 *
 * <p>A failure is retried with a doubling backoff up to the attempt ceiling,
 * then the row is terminally FAILED. A message the mail library cannot even
 * build (bad address syntax, broken MIME) fails at once: sending it again
 * cannot help. Terminal rows lose their subject and body.
 *
 * <p>Delivery is at least once: a crash after the SMTP server accepted the
 * message and before the outcome is written sends it again once the claim
 * lease runs out. For a code or a link a duplicate is harmless; a lost mail
 * is not.
 *
 * <p>Logs carry row ids, attempt counts and exception class names only.
 */
@Service
@Slf4j
public class MailOutboxDispatchService {

    static final String EXHAUSTED = "AttemptsExhausted";
    static final String MISSING_BODY = "MissingBody";
    private static final int MAX_BACKOFF_SHIFT = 20;
    private static final long PURGE_EVERY_MINUTES = 60;

    /** What the read step hands the transactionless send step. */
    private record Work(List<String> to, List<String> cc, List<String> bcc,
                        String subject, String htmlBody) {
    }

    private final MailOutboxRepository repository;
    private final EmailService emailService;
    private final MailOutboxProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final AtomicReference<LocalDateTime> lastPurge = new AtomicReference<>();

    public MailOutboxDispatchService(MailOutboxRepository repository,
                                     EmailService emailService,
                                     MailOutboxProperties properties,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.emailService = emailService;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Send one batch of due mail. Deliberately NOT {@code @Transactional}: see
     * the class javadoc.
     *
     * @return how many were accepted by the SMTP server
     */
    public int dispatchPending() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        housekeeping(now);

        List<UUID> candidates = transactionTemplate.execute(status ->
            repository.findDispatchableIds(MailOutboxStatus.PENDING, properties.getMaxAttempts(),
                now, PageRequest.of(0, properties.getBatchSize())));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (UUID id : candidates) {
            if (dispatchOne(id)) {
                sent++;
            }
        }
        log.info("Mail outbox sweep: {} due, {} sent", candidates.size(), sent);
        return sent;
    }

    private boolean dispatchOne(UUID id) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plusSeconds(properties.getClaimLeaseSeconds());
        Integer claimed = transactionTemplate.execute(status ->
            repository.claim(id, MailOutboxStatus.PENDING, properties.getMaxAttempts(), now, leaseUntil));
        if (!Integer.valueOf(1).equals(claimed)) {
            // Another instance took it, or it was decided since it was listed.
            return false;
        }

        // Nothing to send (row gone, or already emptied): not sent.
        return Optional.ofNullable(transactionTemplate.execute(status -> load(id)))
            .map(work -> sendAndRecord(id, work))
            .orElse(false);
    }

    private boolean sendAndRecord(UUID id, Work work) {
        // No transaction open across the SMTP conversation.
        RuntimeException failure = send(work);
        Boolean sent = transactionTemplate.execute(status -> recordOutcome(id, failure));
        return Boolean.TRUE.equals(sent);
    }

    private RuntimeException send(Work work) {
        try {
            emailService.sendWithAttachment(work.to(), work.cc(), work.bcc(),
                work.subject(), work.htmlBody(), null, null, null);
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    /** Runs inside a short transaction; decrypts the row. */
    private Work load(UUID id) {
        MailOutboxMessage message = repository.findById(id).orElse(null);
        if (message == null) {
            return null;
        }
        if (message.getHtmlBody() == null) {
            // Nothing left to send: never retry an empty row.
            close(message, MailOutboxStatus.FAILED, MISSING_BODY);
            repository.save(message);
            return null;
        }
        return new Work(MailOutboxService.split(message.getRecipients()),
            MailOutboxService.split(message.getCcRecipients()),
            MailOutboxService.split(message.getBccRecipients()),
            message.getSubject(), message.getHtmlBody());
    }

    /** Runs inside a short transaction; the attempt was counted by the claim. */
    private boolean recordOutcome(UUID id, RuntimeException failure) {
        MailOutboxMessage message = repository.findById(id).orElse(null);
        if (message == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (failure == null) {
            close(message, MailOutboxStatus.SENT, null);
            message.setSentAt(now);
            repository.save(message);
            return true;
        }

        String reason = failure.getClass().getSimpleName();
        if (isPermanent(failure) || message.getAttempts() >= properties.getMaxAttempts()) {
            close(message, MailOutboxStatus.FAILED, reason);
            log.error("Mail outbox {} failed permanently after {} attempt(s): {}",
                message.getId(), message.getAttempts(), reason);
        } else {
            message.setLastError(reason);
            message.setNextAttemptAt(now.plusSeconds(backoffSeconds(message.getAttempts())));
            log.warn("Mail outbox {} attempt {}/{} failed, will retry: {}",
                message.getId(), message.getAttempts(), properties.getMaxAttempts(), reason);
        }
        repository.save(message);
        return false;
    }

    /**
     * The message itself is at fault: the library could not build it, or an
     * address is malformed. The same bytes will fail the same way every time.
     */
    static boolean isPermanent(RuntimeException failure) {
        return failure instanceof MailPreparationException
            || failure instanceof MailParseException
            || failure instanceof IllegalArgumentException;
    }

    /** Wait after the given (1-based) failed attempt: initial, doubling, capped. */
    long backoffSeconds(int failedAttempts) {
        int shift = Math.clamp(failedAttempts - 1L, 0, MAX_BACKOFF_SHIFT);
        long delay = properties.getInitialBackoffSeconds() << shift;
        return Math.min(delay, properties.getMaxBackoffSeconds());
    }

    private static void close(MailOutboxMessage message, MailOutboxStatus status, String reason) {
        message.setStatus(status);
        message.setLastError(reason);
        message.setSubject(null);
        message.setHtmlBody(null);
    }

    /**
     * Closes rows whose last claim never reported back, and at most once an
     * hour per instance deletes terminal rows past retention.
     */
    private void housekeeping(LocalDateTime now) {
        Integer exhausted = transactionTemplate.execute(status ->
            repository.closeExhausted(MailOutboxStatus.PENDING, MailOutboxStatus.FAILED,
                properties.getMaxAttempts(), now, EXHAUSTED));
        if (exhausted != null && exhausted > 0) {
            log.error("Mail outbox: {} message(s) used every attempt without an outcome; closed as FAILED",
                exhausted);
        }
        LocalDateTime previous = lastPurge.get();
        if (previous != null && previous.plusMinutes(PURGE_EVERY_MINUTES).isAfter(now)) {
            return;
        }
        if (!lastPurge.compareAndSet(previous, now)) {
            return;
        }
        LocalDateTime cutoff = now.minusDays(properties.getRetentionDays());
        Integer purged = transactionTemplate.execute(status ->
            repository.deleteTerminalBefore(List.of(MailOutboxStatus.SENT, MailOutboxStatus.FAILED), cutoff));
        if (purged != null && purged > 0) {
            log.info("Mail outbox: purged {} terminal row(s) older than {} day(s)",
                purged, properties.getRetentionDays());
        }
    }
}
