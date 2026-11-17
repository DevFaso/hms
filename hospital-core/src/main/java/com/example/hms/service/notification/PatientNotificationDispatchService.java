package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The patient notification sweep (V312), claim-then-send, the mail outbox's mechanics:
 *
 * <ol>
 *   <li>a short transaction lists due ids (PENDING, or RETRYING whose backoff ran out);</li>
 *   <li>each row is claimed by a conditional update in its own short transaction, so a concurrent
 *       sweep on another instance gets 0 rows and skips it: nothing is sent twice by two sweeps;</li>
 *   <li>the message is prepared in a third short transaction ({@link PatientNotificationRenderer}):
 *       the subject still stands, the patient still consents and still has a number or address,
 *       else the row is SKIPPED and nothing is sent;</li>
 *   <li>the SMS gateway or SMTP runs with NO transaction open;</li>
 *   <li>the outcome is recorded in a last short transaction.</li>
 * </ol>
 *
 * <p><b>Bounded retry.</b> A transient failure (provider unreachable, refused credentials, no
 * transport, SMTP down) is RETRYING with a doubling backoff up to the attempt ceiling, then FAILED.
 * A permanent one (refused message, malformed number) is FAILED at once, and so is an
 * {@link PatientNotificationErrorCode#OUTCOME_UNKNOWN unknown outcome}: the provider may have
 * accepted and charged it, so resending automatically could text the patient twice. A FAILED row is
 * listed for the hospital's administrators, who can put it back in the queue.
 *
 * <p>Logs carry row ids, the event, the channel, the attempt count and the error code only: never
 * a number, an address or a message.
 */
@Slf4j
@Service
public class PatientNotificationDispatchService {

    static final List<NotificationDeliveryStatus> OPEN =
        List.of(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.RETRYING);
    private static final int MAX_BACKOFF_SHIFT = 20;

    private final PatientNotificationDeliveryRepository repository;
    private final PatientNotificationRenderer renderer;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PatientNotificationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PatientNotificationDispatchService(PatientNotificationDeliveryRepository repository,
                                              PatientNotificationRenderer renderer,
                                              SmsService smsService,
                                              EmailService emailService,
                                              PatientNotificationProperties properties,
                                              Clock clock,
                                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.renderer = renderer;
        this.smsService = smsService;
        this.emailService = emailService;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Send one batch of due notifications. Deliberately NOT {@code @Transactional}.
     *
     * @return how many the provider or the mail server accepted
     */
    public int dispatchPending() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Integer exhausted = transactionTemplate.execute(status -> repository.closeExhausted(OPEN,
            NotificationDeliveryStatus.FAILED, properties.getMaxAttempts(), now,
            PatientNotificationErrorCode.ATTEMPTS_EXHAUSTED.name()));
        if (exhausted != null && exhausted > 0) {
            log.error("Patient notifications: {} send(s) used every attempt without an outcome; closed as FAILED",
                exhausted);
        }
        List<UUID> candidates = transactionTemplate.execute(status -> repository.findDispatchableIds(OPEN,
            properties.getMaxAttempts(), now, PageRequest.of(0, properties.getBatchSize())));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (UUID id : candidates) {
            if (dispatchOne(id)) {
                sent++;
            }
        }
        log.info("Patient notification sweep: {} due, {} sent", candidates.size(), sent);
        return sent;
    }

    private boolean dispatchOne(UUID id) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime leaseUntil = now.plusSeconds(properties.getClaimLeaseSeconds());
            Integer claimed = transactionTemplate.execute(status ->
                repository.claim(id, OPEN, properties.getMaxAttempts(), now, leaseUntil));
            if (!Integer.valueOf(1).equals(claimed)) {
                return false; // another instance took it, or it was decided since it was listed
            }
            PatientNotificationRenderer.Prepared prepared = transactionTemplate.execute(status -> renderer.prepare(id));
            if (prepared == null) {
                return false; // skipped (recorded by the renderer) or gone
            }
            RuntimeException failure = send(prepared);
            Boolean sent = transactionTemplate.execute(status -> recordOutcome(id, failure));
            return Boolean.TRUE.equals(sent);
        } catch (RuntimeException ex) {
            // A database step failed: the lease brings the row back. Never let one row stop the batch.
            log.warn("Patient notification {} not processed: {}", id, ex.getClass().getSimpleName());
            return false;
        }
    }

    private RuntimeException send(PatientNotificationRenderer.Prepared prepared) {
        try {
            if (prepared.channel() == NotificationChannel.SMS) {
                if (!smsService.deliversRealSms()) {
                    // The logging mock must never be handed patient text.
                    return new com.example.hms.exception.NotificationTransportUnavailableException(
                        "SMS transport is not configured");
                }
                smsService.send(prepared.recipient(), prepared.body());
            } else {
                emailService.sendWithAttachment(List.of(prepared.recipient()), List.of(), List.of(),
                    prepared.subject(), prepared.body(), null, null, null);
            }
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    /** Runs inside a short transaction; the attempt was counted by the claim. */
    private boolean recordOutcome(UUID id, RuntimeException failure) {
        PatientNotificationDelivery row = repository.findById(id).orElse(null);
        if (row == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (failure == null) {
            row.setStatus(NotificationDeliveryStatus.SENT);
            row.setSentAt(now);
            row.setErrorCode(null);
            repository.save(row);
            log.info("Patient notification {} ({} {}) sent on attempt {}", row.getId(), row.getEventType(),
                row.getChannel(), row.getAttempts());
            return true;
        }
        PatientNotificationErrorCode code = PatientNotificationErrorCode.of(failure);
        row.setErrorCode(code.name());
        if (code == PatientNotificationErrorCode.RECIPIENT_NOT_ALLOWED) {
            row.setStatus(NotificationDeliveryStatus.SKIPPED);
            log.info("Patient notification {} ({} {}) skipped: {}", row.getId(), row.getEventType(),
                row.getChannel(), code);
        } else if (code.retryable() && row.getAttempts() < properties.getMaxAttempts()) {
            row.setStatus(NotificationDeliveryStatus.RETRYING);
            row.setNextAttemptAt(now.plusSeconds(backoffSeconds(row.getAttempts())));
            log.warn("Patient notification {} ({} {}) attempt {}/{} failed, will retry: {}", row.getId(),
                row.getEventType(), row.getChannel(), row.getAttempts(), properties.getMaxAttempts(), code);
        } else {
            row.setStatus(NotificationDeliveryStatus.FAILED);
            log.error("Patient notification {} ({} {}) failed after {} attempt(s): {}", row.getId(),
                row.getEventType(), row.getChannel(), row.getAttempts(), code);
        }
        repository.save(row);
        return false;
    }

    /** Wait after the given (1-based) failed attempt: initial, doubling, capped. */
    long backoffSeconds(int failedAttempts) {
        int shift = Math.clamp(failedAttempts - 1L, 0, MAX_BACKOFF_SHIFT);
        long delay = properties.getInitialBackoffSeconds() << shift;
        return Math.min(delay, properties.getMaxBackoffSeconds());
    }
}
