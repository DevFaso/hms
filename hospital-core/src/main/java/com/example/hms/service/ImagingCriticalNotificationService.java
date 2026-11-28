package com.example.hms.service;

import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.i18n.NotificationText;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.service.escalation.UrgentEscalationSupport;
import com.example.hms.service.lab.CriticalResultEscalationLadder;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li><b>It widens, to clinicians.</b> Scorecard K4: the steps are those of the one escalation
 *       ladder ({@link UrgentEscalationSupport}): the covering clinician is added, then the
 *       médecin-chef, then the finding is « non résolu » (an incident is recorded) and the
 *       reminders slow down without ever stopping. The provider stays on the list at every step:
 *       they remain the one who knows the patient. A hospital administrator cannot acknowledge a
 *       finding and reads none of it: the administrators get the incident flag only, which names
 *       no study and no patient.</li>
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

    /** The notice of the last step of the ladder and of every reminder after it: {3} the minutes unacknowledged. */
    static final String UNRESOLVED_KEY = "imaging.critical.unresolved";

    /** SMS is a 320-char channel and carries no findings text (the recall-SMS stance). */
    private static final int IMPRESSION_SNIPPET_LIMIT = 140;

    private final Clock clock;
    private final NotificationService notificationService;
    private final SmsService smsService;
    private final ImagingReportRepository imagingReportRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    /** Scorecard K4: the one escalation ladder (when a step is due, who it adds, the incident). */
    private final UrgentEscalationSupport ladder;

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
        sendSmsBestEffort(report, false);
    }

    /**
     * Takes the step of the ladder that is due for each critical finding still unacknowledged.
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
        LocalDateTime now = LocalDateTime.now(clock);
        closeAnsweredIncidents();
        // No step of the ladder is shorter than its minimum: a report notified or escalated more recently has
        // nothing due. Which step is due is the ladder's to say, from the hospital's own minutes.
        LocalDateTime cutoff = now.minus(Duration.ofMinutes(CriticalResultEscalationLadder.MIN_MINUTES));
        List<ImagingReport> overdue = imagingReportRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (ImagingReport report : overdue) {
            try {
                if (escalateOne(report, now)) {
                    escalated++;
                }
            } catch (RuntimeException ex) {
                log.warn("Critical-imaging escalation failed for report {}: {}",
                    report.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    /** The incident of a finding a clinician has since acknowledged is closed, with that clinician's name on it. */
    private void closeAnsweredIncidents() {
        for (UrgentAlertIncident incident : ladder.openIncidents(UrgentAlertIncident.SOURCE_IMAGING_REPORT)) {
            Optional<ImagingReport> source = imagingReportRepository.findById(incident.getSourceId());
            if (source.filter(this::handedOverToANewerVersion).isPresent()
                    && source.get().getCriticalResultAcknowledgedAt() == null) {
                // QA D4157 (r81): a newer signed version carries the critical finding on its own ladder.
                ladder.closeIncident(UrgentAlertIncident.SOURCE_IMAGING_REPORT, incident.getSourceId(),
                    UrgentAlertIncident.RESOLVED_NO_LONGER_URGENT, null, LocalDateTime.now(clock));
                continue;
            }
            source
                .filter(report -> report.getCriticalResultAcknowledgedAt() != null)
                .ifPresent(report -> {
                    User by = report.getCriticalResultAcknowledgedBy() == null
                        ? null : report.getCriticalResultAcknowledgedBy().getUser();
                    ladder.closeIncident(UrgentAlertIncident.SOURCE_IMAGING_REPORT, report.getId(),
                        UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, by == null ? null : by.getId(),
                        report.getCriticalResultAcknowledgedAt());
                });
        }
    }

    /**
     * QA D4157 (r81): a critical version a later one replaced leaves the ladder only when a newer SIGNED version of
     * the same order is itself flagged critical and was notified: that version's own ladder carries the finding, and
     * one finding does not ask for two acknowledgements. A newer draft, or a signed version without the critical
     * flag, says nothing about the finding (QA D1930): the older version keeps escalating until a clinician
     * acknowledges it.
     */
    boolean handedOverToANewerVersion(ImagingReport report) {
        if (!Boolean.FALSE.equals(report.getLatestVersion()) || report.getImagingOrder() == null
                || report.getImagingOrder().getId() == null) {
            return false;
        }
        Integer version = report.getReportVersion();
        return imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(report.getImagingOrder().getId())
            .stream()
            .filter(other -> other != null && !java.util.Objects.equals(other.getId(), report.getId()))
            .filter(other -> isNewer(other, version))
            .anyMatch(other -> other.isReleasedToPatient() && other.isCriticalFlagged()
                && other.getCriticalNotifiedAt() != null);
    }

    private static boolean isNewer(ImagingReport other, Integer version) {
        if (version != null && other.getReportVersion() != null) {
            return other.getReportVersion() > version;
        }
        return Boolean.TRUE.equals(other.getLatestVersion());
    }

    private boolean escalateOne(ImagingReport report, LocalDateTime now) {
        if (handedOverToANewerVersion(report)) {
            return false;
        }
        int level = report.getCriticalEscalationLevel() == null ? 0 : report.getCriticalEscalationLevel();
        UUID hospitalId = report.getHospital() != null ? report.getHospital().getId() : null;
        Optional<CriticalResultEscalationLadder.Step> due = ladder.due(level, report.getCriticalNotifiedAt(),
            report.getCriticalEscalatedAt(), hospitalId, now);
        if (due.isEmpty()) {
            return false;
        }
        CriticalResultEscalationLadder.Step step = due.get();
        boolean unresolved = UrgentEscalationSupport.unresolved(step);
        long minutes = UrgentEscalationSupport.minutesSince(report.getCriticalNotifiedAt(), now);
        NotificationText message = buildMessage(report, true);
        if (unresolved) {
            Object[] args = java.util.Arrays.copyOf(message.args(), message.args().length + 1);
            args[args.length - 1] = minutes;
            message = NotificationText.of(UNRESOLVED_KEY, args);
        }

        Set<String> chain = escalationRecipients(report, step);
        for (String recipient : chain) {
            notificationService.createNotification(message.key(), message.args(), recipient, ESCALATION_TYPE);
        }
        if (step != CriticalResultEscalationLadder.Step.REMINDER) {
            // One SMS to the ordering provider at each step that widens the circle, and one to the clinicians
            // the step adds; a reminder texts nobody.
            sendSmsBestEffort(report, true);
            Set<String> added = new LinkedHashSet<>(ladder.addedAt(hospitalId, step, resolveOrderingUsername(report)));
            added.removeIf(name -> name.equalsIgnoreCase(resolveOrderingUsername(report)));
            ladder.smsAfterCommit(added, smsInitials(report), report.getId());
        }

        // Stamp even with no resolvable recipient, so the ladder advances and
        // the sweep does not reconsider the row every pass.
        report.setCriticalEscalationLevel((short) Math.min(level + 1, Short.MAX_VALUE));
        report.setCriticalEscalatedAt(now);
        imagingReportRepository.save(report);

        if (unresolved) {
            // On the register of incidents from the last step on; the administrators are flagged once, with
            // no study, no patient and no finding.
            ladder.openIncident(UrgentAlertIncident.SOURCE_IMAGING_REPORT, report.getId(), hospitalId, now, minutes,
                chain.size());
        }
        if (chain.isEmpty()) {
            log.error("Critical imaging finding on report {} reached the step {} with no clinician to tell",
                report.getId(), step);
        }
        return true;
    }

    /**
     * The clinicians a step tells: the ordering provider, the covering clinician, and from the second step the
     * médecin-chef. Never an administrator.
     */
    private Set<String> escalationRecipients(ImagingReport report, CriticalResultEscalationLadder.Step step) {
        Set<String> recipients = new LinkedHashSet<>();

        String ordering = resolveOrderingUsername(report);
        if (ordering != null) {
            recipients.add(ordering);
        }
        UUID hospitalId = report.getHospital() != null ? report.getHospital().getId() : null;
        if (hospitalId != null) {
            recipients.addAll(ladder.seniors(hospitalId, step, ordering));
        } else {
            log.warn("Critical imaging report {} has no resolvable hospital; "
                + "the escalation has only its ordering provider", report.getId());
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

    private void sendSmsBestEffort(ImagingReport report, boolean escalation) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        String phone = resolveOrderingUser(report).map(User::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            // An SMS is sent once, so it is rendered once, in the staff locale. K4: the patient's initials and
            // nothing clinical (never the study, the impression or the full name: MessageDisclosureGuardTest).
            smsService.send(phone, messageSource.getMessage(
                escalation ? "sms.staff.criticalImaging.escalation" : "sms.staff.criticalImaging",
                new Object[] {smsInitials(report)}, NotificationLocales.STAFF));
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging SMS failed for report {}: {}", report.getId(), ex.getMessage());
        }
    }

    /**
     * The in-app alert text. Names the study and the patient and says where to
     * act; the impression is truncated rather than sent whole. Read by the
     * ordering provider (and, on escalation, the covering clinician and the médecin-chef), each in
     * the language they read it in (QA D665). The SMS does not reuse it (K4: it
     * carries the patient's initials and nothing clinical).
     */
    /** K4: the patient as an SMS names them, by initials. */
    private static String smsInitials(ImagingReport report) {
        ImagingOrder order = report.getImagingOrder();
        return com.example.hms.utility.SmsPatientLabel.initials(order != null ? order.getPatient() : null);
    }

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
