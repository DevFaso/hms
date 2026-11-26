package com.example.hms.service.pro;

import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.i18n.NotificationText;
import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Staff;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.service.escalation.UrgentEscalationSupport;
import com.example.hms.service.lab.CriticalResultEscalationLadder;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pro.ProResponseRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
 * The "somebody must look at this" loop for a screening (Tier 2 item 47).
 * Template: {@link com.example.hms.service.CriticalValueNotificationService}
 * — the only escalation chain in the product that actually repeats.
 *
 * <p>Two grades of alert:
 * <ul>
 *   <li><b>Screen-positive</b> (total at/above the instrument's threshold):
 *       one notification to the care team. It is a referral prompt, not an
 *       emergency, and the postpartum plan's outstanding-referral flag
 *       carries it from there.</li>
 *   <li><b>Critical-item-positive</b> (EPDS item 10, thoughts of
 *       self-harm): notified on write, then re-escalated by the sweep until
 *       someone acknowledges it. Scorecard K4: the steps are those of the
 *       one escalation ladder ({@link UrgentEscalationSupport}): the people
 *       who own the patient, then the covering clinician, then the
 *       médecin-chef, then « non résolu » with an incident and reminders
 *       that slow down; no round cap, for the same reason the lab chain has
 *       none — going quiet on a self-harm disclosure is the failure this
 *       exists to prevent. A hospital administrator reads none of it: the
 *       administrators get the incident flag only, which names no
 *       questionnaire and no patient.</li>
 * </ul>
 *
 * <p>Who owns the patient: the recorder (a clinician who administered it
 * is the natural first responder), plus the item-37 panel owners
 * (PRIMARY_PROVIDER and CHW). A self-report from the patient portal has
 * no recorder, and a patient may have no panel — then the alert falls
 * through to every active {@code hms.pro.critical-escalation.fallback-role}
 * at the hospital, so a mother who answered at 2am on her phone is never
 * the one case with nobody on the list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProScreeningEscalationService {

    public static final String SCREEN_POSITIVE_TYPE = "PRO_SCREEN_POSITIVE";
    public static final String CRITICAL_TYPE = "PRO_CRITICAL_ITEM";
    public static final String ESCALATION_TYPE = "PRO_CRITICAL_ITEM_ESCALATION";

    /** The notice of the last step of the ladder and of every reminder after it: {2} the minutes unacknowledged. */
    static final String UNRESOLVED_KEY = "pro.screening.unresolved";

    private final NotificationService notificationService;
    private final SmsService smsService;
    private final ProResponseRepository responseRepository;
    private final PanelAssignmentRepository panelAssignmentRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    /**
     * The escalation clock: it writes {@code notifiedAt}/{@code lastEscalationAt}
     * and computes the sweep cutoff that {@code findCriticalAwaitingEscalation}
     * compares them against; {@code ProResponseService} stamps
     * {@code acknowledgedAt} with the same bean. The query's fallback onto
     * {@code createdAt} (BaseEntity {@code @PrePersist}, system default zone) is
     * the same zone {@code TimeConfig}'s bean uses.
     */
    private final Clock clock;
    /** Scorecard K4: the one escalation ladder (when a step is due, who it adds, the incident). */
    private final UrgentEscalationSupport ladder;

    /** Role notified when a critical response has no recorder and no panel owner. */
    @Value("${hms.pro.critical-escalation.fallback-role:ROLE_MIDWIFE}")
    private String fallbackRole;

    /**
     * Write-time notification. Never propagates: the response is the record
     * and a notification failure must not roll it back — the sweep picks a
     * critical row up anyway, because it keys on {@code acknowledgedAt}.
     *
     * <p>{@code notifiedAt} is stamped only when at least one notification
     * was created. It is what the patient reads as "your care team was
     * alerted", so it must never be set on the strength of the answer
     * alone: a hospital with no recorder, no panel and nobody in the
     * fallback role gets a warning in the log, not a reassurance on the
     * patient's phone.
     */
    public void notifyOnRecord(ProResponse response) {
        try {
            Set<String> recipients;
            if (response.isCriticalItemPositive()) {
                recipients = recipients(response);
                NotificationText text = buildMessage(response, true, false);
                for (String recipient : recipients) {
                    notificationService.createNotification(text.key(), text.args(), recipient, CRITICAL_TYPE);
                }
                sendSmsBestEffort(response, smsText(response, false));
                if (recipients.isEmpty()) {
                    log.warn("Critical PRO response {} has nobody to notify at hospital {}",
                        response.getId(), response.getHospital().getId());
                }
            } else if (response.isScreenPositive()) {
                recipients = recipients(response);
                NotificationText text = buildMessage(response, false, false);
                for (String recipient : recipients) {
                    notificationService.createNotification(text.key(), text.args(), recipient, SCREEN_POSITIVE_TYPE);
                }
            } else {
                return;
            }
            if (!recipients.isEmpty()) {
                response.setNotifiedAt(LocalDateTime.now(clock));
                responseRepository.save(response);
            }
        } catch (RuntimeException ex) {
            log.warn("PRO screening notification failed for response {}: {}",
                response.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * Takes the step of the ladder that is due for each critical response still unacknowledged.
     * Per-row failures are logged and skipped so one bad row never stalls
     * the sweep.
     *
     * @return rows escalated on this pass
     */
    @Transactional
    public int escalateOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        closeAnsweredIncidents();
        // No step of the ladder is shorter than its minimum: a response notified or escalated more recently has
        // nothing due. Which step is due is the ladder's to say, from the hospital's own minutes.
        LocalDateTime cutoff = now.minus(Duration.ofMinutes(CriticalResultEscalationLadder.MIN_MINUTES));
        List<ProResponse> overdue = responseRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (ProResponse response : overdue) {
            try {
                if (escalateOne(response, now)) {
                    escalated++;
                }
            } catch (RuntimeException ex) {
                log.warn("PRO screening escalation failed for response {}: {}",
                    response.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    /** The incident of a response a clinician has since acknowledged is closed, with that clinician on it. */
    private void closeAnsweredIncidents() {
        for (UrgentAlertIncident incident : ladder.openIncidents(UrgentAlertIncident.SOURCE_PRO_RESPONSE)) {
            responseRepository.findById(incident.getSourceId())
                .filter(response -> response.getAcknowledgedAt() != null)
                .ifPresent(response -> ladder.closeIncident(UrgentAlertIncident.SOURCE_PRO_RESPONSE, response.getId(),
                    UrgentAlertIncident.RESOLVED_ACKNOWLEDGED, response.getAcknowledgedByUserId(),
                    response.getAcknowledgedAt()));
        }
    }

    private boolean escalateOne(ProResponse response, LocalDateTime now) {
        UUID hospitalId = response.getHospital() != null ? response.getHospital().getId() : null;
        // A response whose write-time notice reached nobody still starts its ladder, from when it was recorded.
        LocalDateTime since = response.getNotifiedAt() != null ? response.getNotifiedAt() : response.getCreatedAt();
        Optional<CriticalResultEscalationLadder.Step> due = ladder.due(response.getEscalationLevel(), since,
            response.getLastEscalationAt(), hospitalId, now);
        if (due.isEmpty()) {
            return false;
        }
        CriticalResultEscalationLadder.Step step = due.get();
        boolean unresolved = UrgentEscalationSupport.unresolved(step);
        long minutes = UrgentEscalationSupport.minutesSince(since, now);
        NotificationText text = buildMessage(response, true, true);
        if (unresolved) {
            Object[] args = java.util.Arrays.copyOf(text.args(), text.args().length + 1);
            args[args.length - 1] = minutes;
            text = NotificationText.of(UNRESOLVED_KEY, args);
        }
        String recorder = usernameOf(response.getRecordedByUserId());
        Set<String> recipients = recipients(response);
        recipients.addAll(ladder.seniors(hospitalId, step, recorder));
        for (String recipient : recipients) {
            notificationService.createNotification(text.key(), text.args(), recipient, ESCALATION_TYPE);
        }
        if (step != CriticalResultEscalationLadder.Step.REMINDER) {
            // One SMS to the recorder at each step that widens the circle, and one to the clinicians the step
            // adds; a reminder texts nobody.
            sendSmsBestEffort(response, smsText(response, true));
            Set<String> added = new LinkedHashSet<>(ladder.addedAt(hospitalId, step, recorder));
            added.removeIf(name -> name.equalsIgnoreCase(recorder));
            ladder.smsAfterCommit(added, com.example.hms.utility.SmsPatientLabel.initials(response.getPatient()),
                response.getId());
        }

        // Stamp the step even with no resolvable recipient so the ladder
        // advances and the sweep does not reconsider the row every pass —
        // but "somebody was told" only when somebody was.
        response.setEscalationLevel((short) Math.min(response.getEscalationLevel() + 1, Short.MAX_VALUE));
        response.setLastEscalationAt(now);
        if (response.getNotifiedAt() == null && !recipients.isEmpty()) {
            response.setNotifiedAt(now);
        }
        responseRepository.save(response);

        if (unresolved) {
            // On the register of incidents from the last step on; the administrators are flagged once, with
            // no questionnaire and no patient.
            ladder.openIncident(UrgentAlertIncident.SOURCE_PRO_RESPONSE, response.getId(), hospitalId, now, minutes,
                recipients.size());
        }
        if (recipients.isEmpty()) {
            log.error("Critical PRO response {} reached the step {} with no clinician to tell", response.getId(), step);
        }
        return true;
    }

    /**
     * Who owns the patient: recorder + panel owners; the fallback role when
     * that set is empty. The ladder adds the covering clinician and the
     * médecin-chef to them; earlier recipients stay on the list — the point
     * is to widen the net, not hand the problem off. Never an administrator.
     */
    Set<String> recipients(ProResponse response) {
        Set<String> recipients = new LinkedHashSet<>();
        UUID hospitalId = response.getHospital() != null ? response.getHospital().getId() : null;
        UUID patientId = response.getPatient() != null ? response.getPatient().getId() : null;

        String recorder = usernameOf(response.getRecordedByUserId());
        if (recorder != null) {
            recipients.add(recorder);
        }
        if (hospitalId != null && patientId != null) {
            for (PanelRole role : new PanelRole[] {PanelRole.PRIMARY_PROVIDER, PanelRole.CHW}) {
                panelAssignmentRepository
                    .findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                        patientId, hospitalId, role, PanelAssignmentStatus.ACTIVE)
                    .map(this::usernameOf)
                    .ifPresent(recipients::add);
            }
        }
        if (recipients.isEmpty() && hospitalId != null) {
            recipients.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, fallbackRole));
        }
        return recipients;
    }

    private String usernameOf(PanelAssignment assignment) {
        Staff staff = assignment.getProviderStaff();
        User user = staff != null ? staff.getUser() : null;
        return user != null ? user.getUsername() : null;
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getUsername).orElse(null);
    }

    /** One text per round, to the recorder — the person with the chart open. */
    private void sendSmsBestEffort(ProResponse response, String message) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        UUID userId = response.getRecordedByUserId();
        if (userId == null) {
            return;
        }
        String phone = userRepository.findById(userId).map(User::getPhoneNumber).orElse(null);
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            smsService.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("PRO screening SMS failed for response {}: {}", response.getId(), ex.getMessage());
        }
    }

    /**
     * No answers, no score, no item text in the message: a notification row and
     * an SMS are both plaintext. The recipient opens the chart. Read by the
     * recorder, the panel owners or the fallback role — clinical staff, never
     * the caller — each in the language they read it in (QA D665); the SMS is
     * rendered once, in the staff locale.
     */
    /**
     * Scorecard K4: the SMS names neither the instrument (a depression or suicide-risk screen discloses a mental
     * health concern) nor the patient's full name: the patient's initials, and the recipient opens the chart.
     */
    private String smsText(ProResponse response, boolean escalation) {
        String initials = com.example.hms.utility.SmsPatientLabel.initials(response.getPatient());
        return messageSource.getMessage(escalation ? "sms.staff.questionnaire.escalation" : "sms.staff.questionnaire",
            new Object[] {initials}, NotificationLocales.STAFF);
    }

    private NotificationText buildMessage(ProResponse response, boolean critical, boolean escalation) {
        Object instrument = response.getInstrument() != null
            ? response.getInstrument().getCode()
            : NotificationLocalizer.key("pro.screening.instrumentFallback");
        Object patientName = response.getPatient() != null && response.getPatient().getFullName() != null
            ? response.getPatient().getFullName()
            : NotificationLocalizer.key("patient.fallback.generic");
        String key;
        if (escalation) {
            key = "pro.screening.escalation";
        } else if (critical) {
            key = "pro.screening.critical.notification";
        } else {
            key = "pro.screening.positive.notification";
        }
        return NotificationText.of(key, instrument, patientName);
    }
}
