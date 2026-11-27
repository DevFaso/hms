package com.example.hms.service.escalation;

import com.example.hms.model.Hospital;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UrgentAlertIncidentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.lab.CriticalResultEscalationLadder;
import com.example.hms.service.lab.CriticalResultRecipients;
import com.example.hms.utility.ElapsedTime;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Scorecard K4, rule set URGENT_ALERT_ESCALATION (« provisoire »): the ONE ladder of every urgent clinical message
 * that is not a laboratory value. It is the critical-result ladder ({@link CriticalResultEscalationLadder}, the
 * hospital's own minutes) applied to a critical imaging finding, a critical questionnaire answer and every alert of
 * the urgent-alert path (obstetric, newborn, maternal and adult early warning, a severe transfusion reaction, a
 * positive culture, an adult's urgent hypertension, a countersignature past its deadline):
 * <ol>
 *   <li>the first notice goes to the alert's own circle;</li>
 *   <li>then the covering clinician is added (on call, else on duty, else every doctor);</li>
 *   <li>then the médecin-chef;</li>
 *   <li>then the alert is « non résolue »: an incident is recorded, and the administrators get a flag that carries
 *       no patient, no value and no clinical text;</li>
 *   <li>then reminders that slow down to once a day and never stop: only an acknowledgement (or a record that no
 *       longer supports the alert) ends it.</li>
 * </ol>
 * Each source keeps its own circle, texts and acknowledgement; this class holds what they share: when a step is
 * due, the senior clinicians a step adds, the incident, the administrators' flag and the staff SMS (the patient's
 * initials and nothing clinical) to the clinicians a step adds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.URGENT_ALERT_ESCALATION)
public class UrgentEscalationSupport {

    /** The administrators' flag: an incident is open. Its one argument is a number of minutes. */
    public static final String ADMIN_FLAG_KEY = "urgent.alert.unresolved.admin";
    public static final String ADMIN_FLAG_TYPE = "URGENT_ALERT_UNRESOLVED";
    /** The staff SMS of a widening step: {0} the patient's initials. */
    public static final String SMS_KEY = "sms.staff.urgentAlert";

    private final CriticalResultRecipients recipients;
    private final HospitalRepository hospitalRepository;
    private final UrgentAlertIncidentRepository incidents;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    /** The one sender of a clinician's SMS page: best effort, never on the mock transport, never propagating. */
    private final CriticalValueNotificationService criticalPages;

    /**
     * The step due now for an alert first notified at {@code notifiedAt}, or empty: the hospital's own minutes of
     * the critical-result ladder, the defaults for a hospital that cannot be read.
     *
     * @param level the steps already taken (0 after the first notice)
     */
    public Optional<CriticalResultEscalationLadder.Step> due(int level, LocalDateTime notifiedAt,
                                                             LocalDateTime lastEscalatedAt, UUID hospitalId,
                                                             LocalDateTime now) {
        return CriticalResultEscalationLadder.due(level, notifiedAt, lastEscalatedAt, minutesOf(hospitalId), now);
    }

    public CriticalResultEscalationLadder.Minutes minutesOf(UUID hospitalId) {
        return hospital(hospitalId)
            .map(hospital -> CriticalResultEscalationLadder.Minutes.of(hospital.getCriticalCoverAfterMinutes(),
                hospital.getCriticalLeadAfterMinutes(), hospital.getCriticalUnresolvedAfterMinutes()))
            .orElse(CriticalResultEscalationLadder.Minutes.DEFAULT);
    }

    /** From this step on the alert is « non résolue »: its incident is open and its reminders say so. */
    public static boolean unresolved(CriticalResultEscalationLadder.Step step) {
        return step == CriticalResultEscalationLadder.Step.UNRESOLVED
            || step == CriticalResultEscalationLadder.Step.REMINDER;
    }

    /**
     * The senior clinicians a step adds to an alert's own circle: the covering clinician from the first step, the
     * médecin-chef from the second. Never an administrator: {@link #openIncident} flags them, with no clinical text.
     *
     * @param primary the person the alert first went to (the orderer, the documenter): the cover is somebody else
     *                whenever somebody else exists
     */
    public Set<String> seniors(UUID hospitalId, CriticalResultEscalationLadder.Step step, String primary) {
        Set<String> seniors = new LinkedHashSet<>();
        if (hospitalId == null || step == null) {
            return seniors;
        }
        seniors.addAll(recipients.cover(hospitalId, primary));
        if (step != CriticalResultEscalationLadder.Step.COVER) {
            seniors.addAll(medicalLead(hospitalId));
        }
        return seniors;
    }

    /** The clinicians this very step adds: the people a staff SMS goes to. Empty for a reminder. */
    public Set<String> addedAt(UUID hospitalId, CriticalResultEscalationLadder.Step step, String primary) {
        if (hospitalId == null || step == null) {
            return Set.of();
        }
        return switch (step) {
            case COVER -> recipients.cover(hospitalId, primary);
            case MEDICAL_LEAD -> medicalLead(hospitalId);
            default -> Set.of();
        };
    }

    /** The médecin-chef of the hospital (the doctor it named, else its heads of department, else its doctors). */
    public Set<String> medicalLead(UUID hospitalId) {
        return hospital(hospitalId).map(recipients::medicalLead).orElseGet(Set::of);
    }

    /** The covering clinician: on call now, else on duty now, else every doctor; not {@code primary} when another exists. */
    public Set<String> cover(UUID hospitalId, String primary) {
        return hospitalId == null ? Set.of() : recipients.cover(hospitalId, primary);
    }

    /** The laboratory directors of the hospital. */
    public Set<String> biologists(UUID hospitalId) {
        return hospitalId == null ? Set.of() : recipients.biologists(hospitalId);
    }

    /** Whole minutes since the first notice, never negative. */
    public static long minutesSince(LocalDateTime notifiedAt, LocalDateTime now) {
        return notifiedAt == null || now == null ? 0 : Math.max(0, ElapsedTime.between(notifiedAt, now).toMinutes());
    }

    /**
     * « Alerte urgente non résolue »: the incident, one open at a time for an alert; the administrators are flagged
     * once, when it opens, with the minutes and nothing else.
     *
     * @return true when the incident was opened now
     */
    public boolean openIncident(String sourceType, UUID sourceId, UUID hospitalId, LocalDateTime now, long minutes,
                                int told) {
        if (sourceType == null || sourceId == null || hospitalId == null
                || incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(sourceType, sourceId).isPresent()) {
            return false;
        }
        incidents.save(UrgentAlertIncident.builder()
            .sourceType(sourceType)
            .sourceId(sourceId)
            .hospitalId(hospitalId)
            .openedAt(now)
            .minutesUnacknowledged((int) Math.min(Integer.MAX_VALUE, minutes))
            .recipientsTold(told)
            .build());
        log.error("Urgent alert {} {} unresolved after {} minutes at hospital {}: incident opened",
            sourceType, sourceId, minutes, hospitalId);
        for (String administrator : recipients.administrators(hospitalId)) {
            notificationService.createNotification(ADMIN_FLAG_KEY, new Object[]{minutes}, administrator, ADMIN_FLAG_TYPE);
        }
        return true;
    }

    /** The incidents of one kind of source that are still open: a source acknowledged since closes its own. */
    public List<UrgentAlertIncident> openIncidents(String sourceType) {
        return incidents.findBySourceTypeAndResolvedAtIsNull(sourceType);
    }

    /** Closes the open incident of an alert, if any, and says how it ended ({@code UrgentAlertIncident.RESOLVED_*}). */
    public void closeIncident(String sourceType, UUID sourceId, String resolution, UUID byUserId, LocalDateTime at) {
        if (sourceType == null || sourceId == null) {
            return;
        }
        incidents.findBySourceTypeAndSourceIdAndResolvedAtIsNull(sourceType, sourceId).ifPresent(incident -> {
            incident.setResolvedAt(at);
            incident.setResolution(resolution);
            incident.setResolvedByUserId(byUserId);
            // Flushed now: the open slot of the alert (the partial unique index) is free before anything in this
            // transaction could open the next incident.
            incidents.saveAndFlush(incident);
        });
    }

    /**
     * One staff SMS to each of {@code usernames} who has a number, sent once the transaction that decided the step
     * has committed: a gateway that hangs never holds a row or a sweep's lock, and a step that is rolled back texts
     * nobody. The text names the patient by initials and says nothing clinical; the reader opens the application.
     * It goes out through the one sender of a clinician's page, which sends nothing on the mock transport.
     */
    public void smsAfterCommit(Collection<String> usernames, String patientInitials, UUID alertId) {
        if (usernames == null || usernames.isEmpty()) {
            return;
        }
        List<String> lower = usernames.stream().filter(name -> name != null && !name.isBlank())
            .map(name -> name.toLowerCase(Locale.ROOT)).distinct().toList();
        if (lower.isEmpty()) {
            return;
        }
        List<String> phones = userRepository.findByUsernameInIgnoreCase(lower).stream()
            .map(User::getPhoneNumber)
            .filter(phone -> phone != null && !phone.isBlank())
            .distinct()
            .toList();
        if (phones.isEmpty()) {
            return;
        }
        String message = messageSource.getMessage(SMS_KEY, new Object[]{patientInitials}, NotificationLocales.STAFF);
        TransactionCallbacks.afterCommit(
            () -> phones.forEach(phone -> criticalPages.sendCriticalSms(alertId, phone, message)));
    }

    private Optional<Hospital> hospital(UUID hospitalId) {
        return hospitalId == null ? Optional.empty() : hospitalRepository.findById(hospitalId);
    }
}
