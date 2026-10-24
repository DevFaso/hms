package com.example.hms.service;

import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.i18n.NotificationText;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.User;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationLocales;

/**
 * Critical-imaging-finding notification loop (Tier 2 item 27).
 *
 * <p>V132 gave the radiologist a way to raise a critical finding and item 26
 * gave a clinician a way to acknowledge one. Nothing in between told anybody
 * there was something to acknowledge — imaging had the flag, the button, and
 * no chain. This is the chain, modelled directly on
 * {@link CriticalValueNotificationService} so the two behave the same way when
 * a clinician meets them.
 *
 * <p>Two behaviours are copied deliberately because the lab service only
 * acquired them after the 2026-08-21 reassessment found them missing:
 * <ul>
 *   <li><b>It repeats.</b> A one-shot escalation that stamps a flag the sweep
 *       excludes on means an unacknowledged critical finding goes permanently
 *       silent, which is the exact failure this service exists to prevent.
 *       There is no round cap.</li>
 *   <li><b>It widens.</b> Round 1 nudges the ordering provider, who may simply
 *       not have looked. From round 2 they have demonstrably not responded, so
 *       the hospital's admins join — re-notifying only the same person was the
 *       original defect. The provider stays on the list at every round: they
 *       remain the one who can act clinically.</li>
 * </ul>
 *
 * <p><b>No read-back, deliberately.</b> The lab loop demands one because a
 * critical lab value is a number relayed by phone, and repeating it back is
 * what catches a transcription error before somebody treats the wrong figure.
 * A critical imaging finding is prose the clinician reads in the chart; there
 * is no number to mis-transcribe, so a read-back here would be ceremony
 * without a safety property. The radiology analogue — "communicated to Dr X at
 * HH:MM" — is what {@code criticalResultAcknowledgedBy}/{@code ...At} record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagingCriticalNotificationService {

    private static final String NOTIFICATION_TYPE = "CRITICAL_IMAGING_FINDING";
    private static final String ESCALATION_TYPE = "CRITICAL_IMAGING_FINDING_ESCALATION";

    /**
     * Round at which the chain stops being a nudge and starts involving people
     * who did not order the study.
     */
    private static final int TIER_TWO_ROUND = 2;

    /** Tier-2 recipients: accountable for the desk, not for the order. */
    private static final String TIER_TWO_ROLE = "ROLE_HOSPITAL_ADMIN";

    /** SMS is a 320-char channel and carries no findings text (the recall-SMS stance). */
    private static final int IMPRESSION_SNIPPET_LIMIT = 140;

    private final Clock clock;
    private final NotificationService notificationService;
    private final SmsService smsService;
    private final ImagingReportRepository imagingReportRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;

    /** Minutes an unacknowledged critical finding waits before escalation. */
    @Value("${hms.imaging.critical-escalation.escalate-after-minutes:30}")
    private long escalateAfterMinutes;

    /**
     * Notify the ordering provider that a report carries a critical finding.
     *
     * <p>Never propagates: a notification failure must not roll back the
     * clinical write. Same policy as the lab loop and
     * {@code PatientTrackerEventPublisher}.
     *
     * <p>Idempotent on {@code criticalNotifiedAt}, so authoring, revising and
     * signing the same report raises exactly one first alert rather than one
     * per save.
     */
    public void notifyIfCritical(ImagingReport report) {
        try {
            if (report == null || !report.isCriticalFlagged() || report.getCriticalNotifiedAt() != null) {
                return;
            }
            String username = resolveOrderingUsername(report);
            if (username == null) {
                // An externally ingested study can carry no resolvable ordering
                // user. Stamp anyway so the sweep does not spin on the row.
                log.warn("Critical imaging report {} has no resolvable ordering user; skipping notification",
                    report.getId());
            } else {
                sendFirstAlertBestEffort(report, username);
            }
            report.setCriticalNotifiedAt(LocalDateTime.now(clock));
            imagingReportRepository.save(report);
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging notification failed for report {}: {}",
                report != null ? report.getId() : null, ex.getMessage(), ex);
        }
    }

    /**
     * Send the first alert, absorbing a transport failure.
     *
     * <p>The alert is best-effort; the STAMP that follows it is not. The
     * escalation sweep selects on {@code criticalNotifiedAt IS NOT NULL}, so a
     * report left unstamped because the notification bus was down would drop
     * out of the escalation path altogether and the finding would go
     * permanently silent — the exact failure this service exists to prevent.
     * Stamping means the sweep still owns the row and round 1 retries it.
     */
    private void sendFirstAlertBestEffort(ImagingReport report, String username) {
        NotificationText message = buildMessage(report, false);
        try {
            notificationService.createNotification(message.key(), message.args(), username, NOTIFICATION_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging notification failed for report {}; stamping anyway so the "
                + "escalation sweep still owns it: {}", report.getId(), ex.getMessage(), ex);
        }
        sendSmsBestEffort(report, message);
    }

    /**
     * Escalate critical findings still unacknowledged past the configured delay.
     *
     * <p>Per-report failures are logged and skipped so one bad row never stalls
     * the sweep.
     *
     * <p>Locked entry point shared by the scheduled sweep and the manual
     * endpoint (both contend for one ShedLock). Returns {@code null} when
     * ShedLock skipped the run because the lock is held elsewhere; boxed
     * because ShedLock cannot skip a primitive-returning method
     * ({@code SchedulerLockCoverageTest} enforces that for every lock).
     *
     * @return number of reports escalated on this pass, or null when the run was skipped
     */
    @SchedulerLock(name = "ImagingCriticalNotificationService.escalateOverdue", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    @Transactional
    public Integer escalateOverdue() {
        // Fails loudly if a future caller reaches this body around the lock.
        LockAssert.assertLocked();
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(Duration.ofMinutes(escalateAfterMinutes));
        List<ImagingReport> overdue = imagingReportRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (ImagingReport report : overdue) {
            try {
                escalateOne(report);
                escalated++;
            } catch (RuntimeException ex) {
                log.warn("Critical-imaging escalation failed for report {}: {}",
                    report.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    private void escalateOne(ImagingReport report) {
        int round = (report.getCriticalEscalationLevel() == null ? 0 : report.getCriticalEscalationLevel()) + 1;
        NotificationText message = buildMessage(report, true);

        for (String recipient : escalationRecipients(report, round)) {
            notificationService.createNotification(message.key(), message.args(), recipient, ESCALATION_TYPE);
        }
        // SMS once per round, not once per recipient: the transport targets the
        // study's chart context, so fanning it out would send the same text
        // repeatedly for one event.
        sendSmsBestEffort(report, message);

        // Stamp even with no resolvable recipient, so the interval advances and
        // the sweep does not reconsider the row every pass.
        report.setCriticalEscalationLevel((short) Math.min(round, Short.MAX_VALUE));
        report.setCriticalEscalatedAt(LocalDateTime.now(clock));
        imagingReportRepository.save(report);

        if (round >= TIER_TWO_ROUND) {
            log.warn("Critical imaging finding on report {} still unacknowledged after {} escalation round(s)",
                report.getId(), round);
        }
    }

    private Set<String> escalationRecipients(ImagingReport report, int round) {
        Set<String> recipients = new LinkedHashSet<>();

        String ordering = resolveOrderingUsername(report);
        if (ordering != null) {
            recipients.add(ordering);
        }

        if (round >= TIER_TWO_ROUND) {
            UUID hospitalId = report.getHospital() != null ? report.getHospital().getId() : null;
            if (hospitalId != null) {
                recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, TIER_TWO_ROLE));
            } else {
                log.warn("Critical imaging report {} has no resolvable hospital; "
                    + "tier-2 escalation has nobody to notify", report.getId());
            }
        }
        return recipients;
    }

    /**
     * {@code ImagingOrder} holds the ordering provider as a raw user id rather
     * than a {@code Staff} relation, so this resolves through the user table
     * instead of following an association as the lab loop does.
     */
    private Optional<User> resolveOrderingUser(ImagingReport report) {
        ImagingOrder order = report.getImagingOrder();
        UUID userId = order != null ? order.getOrderingProviderUserId() : null;
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId);
    }

    private String resolveOrderingUsername(ImagingReport report) {
        return resolveOrderingUser(report).map(User::getUsername).orElse(null);
    }

    private void sendSmsBestEffort(ImagingReport report, NotificationText text) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        String phone = resolveOrderingUser(report).map(User::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            // An SMS is sent once, so it is rendered once, in the staff locale.
            smsService.send(phone, text.render(messageSource, NotificationLocales.STAFF));
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging SMS failed for report {}: {}", report.getId(), ex.getMessage());
        }
    }

    /**
     * The alert text. Names the study and the patient and says where to act; the
     * impression is truncated rather than sent whole, because this same string
     * goes down an SMS channel. Read by the ordering provider (and, on
     * escalation, the desk's administrators), each in the language they read
     * it in (QA D665); the SMS is rendered once, in the staff locale.
     */
    private NotificationText buildMessage(ImagingReport report, boolean escalation) {
        ImagingOrder order = report.getImagingOrder();
        return NotificationText.of(
            escalation ? "imaging.critical.escalation" : "imaging.critical.notification",
            studyLabel(report, order), patientLabel(order), impressionSnippet(report));
    }

    /** The report's own title if it has one, else the ordered study type. */
    private Object studyLabel(ImagingReport report, ImagingOrder order) {
        if (report.getReportTitle() != null && !report.getReportTitle().isBlank()) {
            return report.getReportTitle();
        }
        if (order != null && order.getStudyType() != null && !order.getStudyType().isBlank()) {
            return order.getStudyType();
        }
        return NotificationLocalizer.key("imaging.critical.studyFallback");
    }

    /**
     * The patient's name, or a neutral placeholder.
     *
     * <p>Guards the NAME, not just the patient reference: a patient row with no
     * recorded name rendered the literal string "null" into an alert a clinician
     * reads and an SMS that goes out of the building.
     */
    private Object patientLabel(ImagingOrder order) {
        if (order != null && order.getPatient() != null) {
            String fullName = order.getPatient().getFullName();
            if (fullName != null && !fullName.isBlank()) {
                return fullName;
            }
        }
        return NotificationLocalizer.key("patient.fallback.generic");
    }

    private String impressionSnippet(ImagingReport report) {
        String impression = report.getImpression();
        if (impression == null || impression.isBlank()) {
            return "";
        }
        String trimmed = impression.trim();
        if (trimmed.length() > IMPRESSION_SNIPPET_LIMIT) {
            trimmed = trimmed.substring(0, IMPRESSION_SNIPPET_LIMIT).trim() + "…";
        }
        return " " + trimmed;
    }
}
