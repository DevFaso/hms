package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.repository.LabResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationLocales;

/**
 * Critical-value notification loop (P0 #5).
 * <p>
 * Before this service, critical lab results were flagged and acknowledgeable
 * but nothing ever told the ordering provider — the loop that makes the flag
 * safe did not exist. Now: on result save, a critical value notifies the
 * ordering provider (in-app STOMP push, plus SMS when the IKODDI channel is
 * live); a sweep escalates results still unresolved after a configurable delay.
 * <p>
 * The escalation is a CHAIN and it REPEATS. Both were previously untrue: it
 * re-notified the same ordering provider who had already ignored the first
 * alert, then stamped a flag the sweep query excluded on — so a critical result
 * nobody acknowledged produced two notifications to one person and then went
 * permanently quiet. Round 1 nudges the provider; from round 2 the hospital's
 * admins are added; it keeps firing on the interval until somebody resolves it.
 * Going silent on an unacknowledged critical value is the failure mode this
 * whole service exists to prevent, so there is deliberately no round cap.
 * <p>
 * Acknowledgement is NOT the read-back — that claim used to sit in this javadoc
 * and was simply false. A read-back is the receiver repeating the value so the
 * system can check it, which is what catches a transcription error;
 * "acknowledged: true" proves a button was clicked. See
 * {@code recordReadBack}.
 * <p>
 * "Critical" matches the existing critical worklist endpoints: a persisted
 * HL7 {@link AbnormalFlag#CRITICAL}, or a computed severity of CRITICAL/HIGH.
 */
@Slf4j
@Service
public class CriticalValueNotificationService {

    private static final String NOTIFICATION_TYPE = "CRITICAL_LAB_RESULT";
    private static final String ESCALATION_TYPE = "CRITICAL_LAB_RESULT_ESCALATION";

    /**
     * Round at which the chain stops being a nudge and starts involving people
     * who did not order the test.
     */
    private static final int TIER_TWO_ROUND = 2;

    /** Tier-2 recipients: accountable for the desk, not for the order. */
    private static final String TIER_TWO_ROLE = "ROLE_HOSPITAL_ADMIN";

    private final NotificationService notificationService;
    private final SmsService smsService;
    private final LabResultRepository labResultRepository;
    private final LabResultMapper labResultMapper;
    private final com.example.hms.repository.StaffRepository staffRepository;
    private final MessageSource messageSource;

    /**
     * Commits the mismatch record even though the caller's transaction is about
     * to roll back. See {@link #recordReadBack}: a mismatched read-back throws,
     * and without REQUIRES_NEW the throw would erase the very row that proves
     * the mismatch happened — which is what it silently did until the 2026-08-21
     * reassessment caught the write-only column.
     */
    private final org.springframework.transaction.support.TransactionTemplate mismatchTx;

    /** Minutes an unacknowledged critical result waits before escalation. */
    @Value("${hms.lab.critical-escalation.escalate-after-minutes:30}")
    private long escalateAfterMinutes;

    public CriticalValueNotificationService(
        NotificationService notificationService,
        SmsService smsService,
        LabResultRepository labResultRepository,
        LabResultMapper labResultMapper,
        com.example.hms.repository.StaffRepository staffRepository,
        org.springframework.transaction.PlatformTransactionManager transactionManager,
        MessageSource messageSource
    ) {
        this.notificationService = notificationService;
        this.smsService = smsService;
        this.labResultRepository = labResultRepository;
        this.labResultMapper = labResultMapper;
        this.staffRepository = staffRepository;
        this.messageSource = messageSource;
        this.mismatchTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.mismatchTx.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Notify the ordering provider when a freshly saved result is critical.
     * Never propagates — a notification failure must not roll back the
     * clinical write (same policy as PatientTrackerEventPublisher).
     */
    public void notifyIfCritical(LabResult result) {
        notifyIfCritical(result, null);
    }

    /**
     * Same as {@link #notifyIfCritical(LabResult)} with the severity the caller
     * already computed from the mapper, so the entry path and this check agree
     * on one value; {@code null} means compute it here.
     *
     * <p><b>Runs in the caller's transaction, deliberately.</b> The alert row
     * and the {@code criticalNotifiedAt} stamp are ordinary local writes —
     * they were never the reason to defer anything — so they commit with the
     * result itself: if the result is on the chart, the provider has been
     * told, and there is no window in which a restart loses the alert. Only
     * the SMS is deferred, because it is the one blocking network hop, and it
     * is the one thing that can be retried by hand if it is lost.
     */
    public void notifyIfCritical(LabResult result, String severityFlag) {
        // NOT wrapped in a catch. This runs in the caller's transaction — the
        // alert row and the stamp commit with the result, which is what
        // guarantees no result reaches the chart un-alerted — and a
        // persistence failure in here marks that transaction rollback-only
        // whatever this method does with the exception. Catching it therefore
        // bought nothing and lied: the caller believed the notification was
        // contained, then got a 500 at commit with the cause logged as a
        // warning (the #553 trap, and the same one the outbox enqueue had).
        // Failing loudly means the clinical write is retried, which is
        // recoverable; a critical result nobody was told about is not.
        if (result.getCriticalNotifiedAt() != null || !isCritical(result, severityFlag)) {
            return;
        }
        String username = resolveOrderingUsername(result);
        if (username == null) {
            // SYSTEM-actor results can arrive on orders whose staff has no
            // user account; stamp anyway so the escalation sweep does not
            // spin on them.
            log.warn("Critical lab result {} has no resolvable ordering user; skipping notification",
                result.getId());
        } else {
            String message = buildMessage(result, false);
            notificationService.createNotification(message, username, NOTIFICATION_TYPE);
            // The gateway call waits for the commit: a hung gateway must
            // not hold the clinical transaction's row locks, and an SMS
            // for a result that then rolled back would be worse than a
            // late one. Everything it needs is read HERE, while the
            // transaction is open — the number is three lazy hops away
            // (order, ordering staff, user) and the callback must not go
            // looking for them.
            UUID resultId = result.getId();
            String phoneNumber = resolveOrderingPhone(result);
            com.example.hms.utility.TransactionCallbacks.afterCommit(
                () -> sendCriticalSms(resultId, phoneNumber, message));
        }
        result.setCriticalNotifiedAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        labResultRepository.save(result);
    }

    /**
     * The deferred half: the SMS, sent once the caller's transaction has
     * committed.
     *
     * <p>It is handed the number and the text rather than an id to re-read,
     * and that is the point. The previous version reloaded the result "because
     * the persistence context is gone", which was not true — {@code
     * afterCommit} runs before Spring unbinds the EntityManager, so the reload
     * was served from the first-level cache and re-attached nothing. The code
     * worked for a reason its own comment denied, and had the comment been
     * true the number — three lazy hops away, with open-in-view off — would
     * have been unreachable and the SMS dropped as a caught warning. Reading
     * it in the transaction removes the question.
     *
     * <p>Deliberately NOT annotated {@code @Transactional}: there is nothing
     * transactional left here, and it is self-invoked from
     * {@link #notifyIfCritical}, where an annotation would be inert anyway.
     *
     * <p>Never propagates: the in-app alert and the stamp are already
     * committed, so a gateway failure must not surface as a 500 on a result
     * that is safely on the chart.
     */
    public void sendCriticalSms(UUID resultId, String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank() || !smsService.deliversRealSms()) {
            return;
        }
        try {
            smsService.send(phoneNumber, message);
        } catch (RuntimeException ex) {
            log.warn("Critical-value SMS failed for lab result {}: {}", resultId, ex.getMessage(), ex);
        }
    }

    /** The ordering provider's number, read while the session is open. */
    private String resolveOrderingPhone(LabResult result) {
        LabOrder order = result.getLabOrder();
        Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
        User user = orderingStaff != null ? orderingStaff.getUser() : null;
        return user != null ? user.getPhoneNumber() : null;
    }

    /**
     * Escalate critical results still unresolved past the configured delay.
     *
     * <p>Repeats on the interval rather than firing once, and widens the
     * audience as rounds pass. Per-result failures are logged and skipped so one
     * bad row never stalls the sweep.
     *
     * <p>Locked entry point shared by the scheduled sweep and the manual
     * endpoint (both contend for one ShedLock). Returns {@code null} when
     * ShedLock skipped the run because the lock is held elsewhere; boxed
     * because ShedLock cannot skip a primitive-returning method
     * ({@code SchedulerLockCoverageTest} enforces that for every lock).
     *
     * @return number of results escalated on this pass, or null when the run was skipped
     */
    @SchedulerLock(name = "CriticalValueNotificationService.escalateOverdue", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    @Transactional
    public Integer escalateOverdue() {
        // Fails loudly if a future caller reaches this body around the lock.
        LockAssert.assertLocked();
        LocalDateTime cutoff = LocalDateTime.now(java.time.ZoneId.systemDefault()).minus(Duration.ofMinutes(escalateAfterMinutes));
        List<LabResult> overdue = labResultRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (LabResult result : overdue) {
            try {
                escalateOne(result);
                escalated++;
            } catch (RuntimeException ex) {
                log.warn("Critical-value escalation failed for lab result {}: {}",
                    result.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    private void escalateOne(LabResult result) {
        int round = result.getCriticalEscalationLevel() + 1;
        String message = buildMessage(result, true);

        for (String recipient : escalationRecipients(result, round)) {
            notificationService.createNotification(message, recipient, ESCALATION_TYPE);
        }
        // SMS once per round, not once per recipient: the transport targets the
        // result's chart context, so fanning it out would send the same text
        // repeatedly for one event.
        sendSmsBestEffort(result, message);

        // Stamp even with no resolvable recipient, so the interval still
        // advances and the sweep does not reconsider the row every pass.
        result.setCriticalEscalationLevel((short) Math.min(round, Short.MAX_VALUE));
        result.setCriticalEscalatedAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        labResultRepository.save(result);

        if (round >= TIER_TWO_ROUND) {
            log.warn("Critical lab result {} still unresolved after {} escalation round(s)",
                result.getId(), round);
        }
    }

    /**
     * Who hears about round {@code round}.
     *
     * <p>Round 1 is a nudge to the ordering provider — they may simply not have
     * looked yet. From round 2 the provider has demonstrably not responded, so
     * the hospital's admins are added; re-notifying only the same person was the
     * defect this replaces.
     *
     * <p>The provider stays on the list at every round rather than being
     * dropped: they remain the person who can act clinically, and the point is
     * to widen the net, not hand the problem off.
     */
    private java.util.Set<String> escalationRecipients(LabResult result, int round) {
        java.util.Set<String> recipients = new java.util.LinkedHashSet<>();

        String ordering = resolveOrderingUsername(result);
        if (ordering != null) {
            recipients.add(ordering);
        }

        if (round >= TIER_TWO_ROUND) {
            UUID hospitalId = resolveHospitalId(result);
            if (hospitalId != null) {
                recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(
                    hospitalId, TIER_TWO_ROLE));
            } else {
                log.warn("Critical lab result {} has no resolvable hospital; "
                    + "tier-2 escalation has nobody to notify", result.getId());
            }
        }
        return recipients;
    }

    private UUID resolveHospitalId(LabResult result) {
        LabOrder order = result.getLabOrder();
        if (order == null || order.getHospital() == null) {
            return null;
        }
        return order.getHospital().getId();
    }

    /**
     * Record the receiving clinician's read-back of a critical value.
     *
     * <p>This is the link P0 #5 asked for and never got. The acknowledge
     * endpoint takes no body, so nothing recorded WHAT the clinician was told —
     * only that they clicked. A read-back means the receiver repeats the value
     * and the system checks it, which is what catches a transcription error
     * before somebody treats the wrong number.
     *
     * <p>A mismatch is REJECTED but still persisted, because a clinician reading
     * back the wrong value is precisely the event worth having a record of. The
     * persistence runs in its OWN transaction ({@code REQUIRES_NEW}): the
     * BusinessException below rolls back the caller's transaction, and until the
     * 2026-08-21 reassessment that rollback silently erased the mismatch row this
     * method had just written — the audit record existed only in the log line.
     *
     * @return the updated result
     * @throws com.example.hms.exception.BusinessException when the repeated
     *         value does not match the reported one
     */
    @Transactional
    public LabResult recordReadBack(LabResult result, String repeatedValue,
                                    UUID byUserId, String byDisplay) {
        String reported = normalizeForComparison(result.getResultValue());
        String repeated = normalizeForComparison(repeatedValue);

        result.setCriticalReadBackValue(repeatedValue);
        result.setCriticalReadBackByUserId(byUserId);
        result.setCriticalReadBackByDisplay(byDisplay);

        if (reported == null || repeated == null || !reported.equals(repeated)) {
            // Copy the mismatch onto a fresh row loaded inside the inner
            // transaction rather than saving the caller's managed entity: the
            // outer session still owns `result`, and committing it here while
            // the outer transaction rolls back would leave the two sessions
            // disagreeing about the entity's state.
            UUID resultId = result.getId();
            mismatchTx.executeWithoutResult(status ->
                labResultRepository.findById(resultId).ifPresent(row -> {
                    row.setCriticalReadBackValue(repeatedValue);
                    row.setCriticalReadBackByUserId(byUserId);
                    row.setCriticalReadBackByDisplay(byDisplay);
                    labResultRepository.save(row);
                }));
            log.warn("Critical-value read-back MISMATCH on lab result {}: reported '{}', repeated '{}'",
                result.getId(), result.getResultValue(), repeatedValue);
            throw new com.example.hms.exception.BusinessException(
                "Read-back does not match the reported result. Confirm the value with the laboratory "
                    + "before acting on it.");
        }

        // Only a MATCHING read-back resolves the result and stops escalation.
        result.setCriticalReadBackAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        result.setAcknowledged(true);
        result.setAcknowledgedAt(LocalDateTime.now(java.time.ZoneId.systemDefault()));
        result.setAcknowledgedByUserId(byUserId);
        result.setAcknowledgedByDisplay(byDisplay);
        return labResultRepository.save(result);
    }

    /**
     * Compare on trimmed, case-folded text with trailing zeros normalised, so
     * "3.40" reads back "3.4" — a clinician repeating the number correctly must
     * not be told they got it wrong.
     */
    private static String normalizeForComparison(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            return new java.math.BigDecimal(trimmed).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notNumeric) {
            return trimmed;
        }
    }

    /** Same semantics as the /lab-results/hospital/{id}/critical endpoints. */
    private boolean isCritical(LabResult result, String knownSeverity) {
        if (result.getAbnormalFlag() == AbnormalFlag.CRITICAL) {
            return true;
        }
        String severity = knownSeverity;
        if (severity == null) {
            LabResultResponseDTO dto = labResultMapper.toResponseDTO(result);
            severity = dto != null ? dto.getSeverityFlag() : null;
        }
        return "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity);
    }

    private String resolveOrderingUsername(LabResult result) {
        LabOrder order = result.getLabOrder();
        Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
        User user = orderingStaff != null ? orderingStaff.getUser() : null;
        return user != null ? user.getUsername() : null;
    }

    private void sendSmsBestEffort(LabResult result, String message) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        LabOrder order = result.getLabOrder();
        Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
        User user = orderingStaff != null ? orderingStaff.getUser() : null;
        String phone = user != null ? user.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            smsService.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("Critical-value SMS failed for lab result {}: {}", result.getId(), ex.getMessage());
        }
    }

    /**
     * Read by the ordering provider (and, on escalation, the desk's
     * administrators) — never by the caller, and a sweep has no request
     * locale anyway — so the body is rendered in the staff locale.
     */
    private String buildMessage(LabResult result, boolean escalation) {
        java.util.Locale locale = NotificationLocales.STAFF;
        LabOrder order = result.getLabOrder();
        String testName = order != null && order.getLabTestDefinition() != null
            ? order.getLabTestDefinition().getName()
            : messageSource.getMessage("lab.test.fallback", null, locale);
        String patientName = order != null && order.getPatient() != null
            ? order.getPatient().getFullName()
            : messageSource.getMessage("patient.fallback.generic", null, locale);
        String value = result.getResultValue()
            + (result.getResultUnit() != null ? " " + result.getResultUnit() : "");
        return messageSource.getMessage(
            escalation ? "lab.critical.escalation" : "lab.critical.notification",
            new Object[]{testName, value, patientName},
            locale);
    }
}
