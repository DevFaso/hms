package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.repository.PatientNotificationDeliveryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AppointmentReminderService;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Queues patient notifications in the delivery ledger (V312); the dispatch sweep
 * ({@link PatientNotificationDispatchService}) sends them.
 *
 * <p><b>Which channels.</b> SMS when this deployment really sends SMS
 * ({@code deliversRealSms()}: the logging mock must never be handed patient text) and the patient
 * has a number; email, for the events that send one, when a mail transport exists and the patient
 * has an address. Each only when the patient has not turned that channel off for this kind of
 * notice ({@link PatientChannelConsent}); a channel turned off gets a SKIPPED / OPTED_OUT row instead,
 * so the refusal is visible to staff (QA D2206). Consent and contact are checked again at send time.
 *
 * <p><b>Never twice.</b> One row per {@code event:subject:channel}, enforced by a unique index:
 * the same release reached by two paths, a scheduler re-run or a second instance finds the row
 * already there and queues nothing.
 *
 * <p><b>Never in the way.</b> Every write runs in a transaction of its own and every failure is
 * logged (ids and exception class only) and swallowed: a notification must never fail or roll back
 * the booking, release or sweep that asked for it.
 */
@Slf4j
@Service
public class PatientNotificationLedger {

    private final PatientNotificationDeliveryRepository repository;
    private final PatientRepository patientRepository;
    private final PatientChannelConsent consent;
    private final SmsService smsService;
    private final EmailService emailService;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    public PatientNotificationLedger(PatientNotificationDeliveryRepository repository,
                                     PatientRepository patientRepository,
                                     PatientChannelConsent consent,
                                     SmsService smsService,
                                     EmailService emailService,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.consent = consent;
        this.smsService = smsService;
        this.emailService = emailService;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Queue once the caller's transaction commits (at once when there is none). Pass ids only: the
     * caller's persistence context is gone when this runs.
     */
    public void requestAfterCommit(PatientNotificationEvent event, UUID hospitalId, UUID patientId, UUID subjectId) {
        if (event == null || hospitalId == null || patientId == null || subjectId == null) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> enqueue(event, hospitalId, patientId, subjectId));
    }

    /**
     * The booking confirmation SMS of a just-saved appointment, once the booking commits. Only a
     * live booking ({@link AppointmentReminderService#REMINDABLE_STATUSES}): a PENDING follow-up or a
     * booking written straight as cancelled is not confirmed. Call it inside the booking
     * transaction; only ids leave it.
     */
    public void confirmBooking(Appointment saved) {
        if (saved == null || saved.getId() == null || saved.getPatient() == null || saved.getHospital() == null
            || !AppointmentReminderService.REMINDABLE_STATUSES.contains(saved.getStatus())) {
            return;
        }
        requestAfterCommit(PatientNotificationEvent.APPOINTMENT_CONFIRMATION, saved.getHospital().getId(),
            saved.getPatient().getId(), saved.getId());
    }

    /**
     * Queue now, each channel in its own transaction. A channel the patient turned off for this
     * kind of notice is not queued: it gets a SKIPPED row with reason OPTED_OUT instead (QA D2206),
     * so the hospital's administrators can see the patient was not told, and why.
     *
     * @return the channels a row was queued for (empty when none, or when they already existed)
     */
    public List<NotificationChannel> enqueue(PatientNotificationEvent event, UUID hospitalId, UUID patientId,
                                             UUID subjectId) {
        if (event == null || hospitalId == null || patientId == null || subjectId == null || !event.isQueued()) {
            return List.of();
        }
        try {
            List<ChannelDecision> decisions = ownTransaction.execute(status -> eligibleChannels(event, patientId));
            List<NotificationChannel> queued = new ArrayList<>();
            for (ChannelDecision decision : decisions == null ? List.<ChannelDecision>of() : decisions) {
                if (decision.optedOut()) {
                    insertOnce(event, hospitalId, patientId, subjectId, decision.channel(),
                        PatientNotificationErrorCode.OPTED_OUT);
                } else if (insertOnce(event, hospitalId, patientId, subjectId, decision.channel(), null)) {
                    queued.add(decision.channel());
                }
            }
            return queued;
        } catch (RuntimeException ex) {
            log.warn("Patient notification {} for subject {} not queued: {}", event, subjectId,
                ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * The trace of a send the patient's opt-out blocked (QA D2206): a SKIPPED row, reason
     * OPTED_OUT, never a message, number or address. Written at once in a transaction of its own,
     * so it survives a caller that then refuses the request (the invoice e-mail). Once per
     * {@code event:subject:channel}, like every row. Never throws.
     */
    public void recordOptOut(PatientNotificationEvent event, UUID hospitalId, UUID patientId, UUID subjectId,
                             NotificationChannel channel) {
        recordSkipped(event, hospitalId, patientId, subjectId, channel, PatientNotificationErrorCode.OPTED_OUT);
    }

    public void recordUnverified(PatientNotificationEvent event, UUID hospitalId, UUID patientId, UUID subjectId,
                                 NotificationChannel channel) {
        recordSkipped(event, hospitalId, patientId, subjectId, channel, PatientNotificationErrorCode.RECIPIENT_UNVERIFIED);
    }

    private void recordSkipped(PatientNotificationEvent event, UUID hospitalId, UUID patientId, UUID subjectId,
                               NotificationChannel channel, PatientNotificationErrorCode reason) {
        if (event == null || hospitalId == null || patientId == null || subjectId == null || channel == null) {
            return;
        }
        try {
            insertOnce(event, hospitalId, patientId, subjectId, channel, reason);
        } catch (RuntimeException ex) {
            log.warn("Opt-out trace of {} for subject {} not written: {}", event, subjectId,
                ex.getClass().getSimpleName());
        }
    }

    /** One channel the patient can be reached on, and whether they turned it off for this notice. */
    private record ChannelDecision(NotificationChannel channel, boolean optedOut) {
    }

    /**
     * The channels this deployment can send on and the patient has a contact for, each with the
     * patient's choice. A channel with no transport or no contact is not a refusal: no row.
     */
    private List<ChannelDecision> eligibleChannels(PatientNotificationEvent event, UUID patientId) {
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) {
            return List.of();
        }
        UUID userId = patient.getUser() != null ? patient.getUser().getId() : null;
        List<ChannelDecision> channels = new ArrayList<>(2);
        if (smsService.deliversRealSms() && StringUtils.hasText(patient.getPhoneNumberPrimary())) {
            channels.add(new ChannelDecision(NotificationChannel.SMS,
                !consent.allows(userId, event.preferenceType(), NotificationChannel.SMS)));
        }
        if (event.sendsEmail() && emailService.deliversRealEmail() && StringUtils.hasText(patient.getEmail())) {
            channels.add(new ChannelDecision(NotificationChannel.EMAIL,
                !consent.allows(userId, event.preferenceType(), NotificationChannel.EMAIL)));
        }
        return channels;
    }

    /**
     * @param skipped null to queue the row; a reason to write it SKIPPED (terminal) instead
     * @return true when this call created the row.
     */
    private boolean insertOnce(PatientNotificationEvent event, UUID hospitalId, UUID patientId, UUID subjectId,
                               NotificationChannel channel, PatientNotificationErrorCode skipped) {
        String key = PatientNotificationDelivery.keyOf(event, subjectId, channel);
        try {
            Boolean created = ownTransaction.execute(status -> {
                if (repository.existsByIdempotencyKey(key)) {
                    return false;
                }
                var refusal = skipped == PatientNotificationErrorCode.OPTED_OUT
                    ? patientRepository.findNotificationAccountScoped(patientId, hospitalId)
                        .flatMap(accountId -> consent.refusal(accountId, event.preferenceType(), channel))
                    : java.util.Optional.<com.example.hms.model.NotificationPreference>empty();
                repository.saveAndFlush(PatientNotificationDelivery.builder()
                    .hospitalId(hospitalId)
                    .patientId(patientId)
                    .eventType(event)
                    .subjectId(subjectId)
                    .channel(channel)
                    .idempotencyKey(key)
                    .status(skipped == null ? NotificationDeliveryStatus.PENDING : NotificationDeliveryStatus.SKIPPED)
                    .errorCode(skipped == null ? null : skipped.name())
                    .consentRecordedBy(refusal.map(com.example.hms.model.NotificationPreference::getRecordedByUserId).orElse(null))
                    .consentRecordedAt(refusal.map(com.example.hms.model.NotificationPreference::getRecordedAt).orElse(null))
                    .nextAttemptAt(LocalDateTime.now(clock))
                    .build());
                return true;
            });
            return Boolean.TRUE.equals(created);
        } catch (DataIntegrityViolationException raced) {
            // Another instance inserted the same key between the check and the insert: it is queued once.
            return false;
        }
    }
}
