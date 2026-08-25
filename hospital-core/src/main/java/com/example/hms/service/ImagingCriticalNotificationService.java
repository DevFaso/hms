package com.example.hms.service;

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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    private final NotificationService notificationService;
    private final SmsService smsService;
    private final ImagingReportRepository imagingReportRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;

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
                String message = buildMessage(report, false);
                notificationService.createNotification(message, username, NOTIFICATION_TYPE);
                sendSmsBestEffort(report, message);
            }
            report.setCriticalNotifiedAt(LocalDateTime.now());
            imagingReportRepository.save(report);
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging notification failed for report {}: {}",
                report != null ? report.getId() : null, ex.getMessage(), ex);
        }
    }

    /**
     * Escalate critical findings still unacknowledged past the configured delay.
     *
     * <p>Per-report failures are logged and skipped so one bad row never stalls
     * the sweep.
     *
     * @return number of reports escalated on this pass
     */
    @Transactional
    public int escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMinutes(escalateAfterMinutes));
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
        String message = buildMessage(report, true);

        for (String recipient : escalationRecipients(report, round)) {
            notificationService.createNotification(message, recipient, ESCALATION_TYPE);
        }
        // SMS once per round, not once per recipient: the transport targets the
        // study's chart context, so fanning it out would send the same text
        // repeatedly for one event.
        sendSmsBestEffort(report, message);

        // Stamp even with no resolvable recipient, so the interval advances and
        // the sweep does not reconsider the row every pass.
        report.setCriticalEscalationLevel((short) Math.min(round, Short.MAX_VALUE));
        report.setCriticalEscalatedAt(LocalDateTime.now());
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

    private void sendSmsBestEffort(ImagingReport report, String message) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        String phone = resolveOrderingUser(report).map(User::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            smsService.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("Critical-imaging SMS failed for report {}: {}", report.getId(), ex.getMessage());
        }
    }

    /**
     * The alert text. Names the study and the patient and says where to act; the
     * impression is truncated rather than sent whole, because this same string
     * goes down an SMS channel.
     */
    private String buildMessage(ImagingReport report, boolean escalation) {
        ImagingOrder order = report.getImagingOrder();
        String study = report.getReportTitle() != null && !report.getReportTitle().isBlank()
            ? report.getReportTitle()
            : (order != null && order.getStudyType() != null ? order.getStudyType() : "Imaging study");
        String patientName = order != null && order.getPatient() != null
            ? order.getPatient().getFullName() : "patient";
        String prefix = escalation
            ? "ESCALATION - unacknowledged critical imaging finding: "
            : "Critical imaging finding: ";
        return prefix + study + " for " + patientName + "." + impressionSnippet(report)
            + " Review and acknowledge in HMS.";
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
