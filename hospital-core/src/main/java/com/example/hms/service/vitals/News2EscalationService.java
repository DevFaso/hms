package com.example.hms.service.vitals;

import com.example.hms.model.Admission;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.NewsScoreCalculator;
import com.example.hms.utility.NewsScoreCalculator.NewsScoreResult;
import com.example.hms.utility.NewsScoreCalculator.RiskBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D207: a NEWS2 score in the MEDIUM or HIGH band (RCP NEWS2 escalation
 * matrix) must reach the responsible clinicians and tighten the observation
 * interval. Before this, the score was stored and nothing acted on it: no
 * alert, and the next vitals check stayed routine.
 *
 * <p>The alert goes to the recording nurse and to the stay's attending
 * physician (or the admitting provider when no attending is named). It runs
 * in the caller's transaction, like the critical-lab alert: if the vitals row
 * commits, so does the alert.
 */
@Service
@RequiredArgsConstructor
public class News2EscalationService {

    public static final String NOTIFICATION_TYPE = "NEWS2_ESCALATION";

    /** RCP NEWS2: HIGH = continuous monitoring; recorded here as a 15-minute round. */
    static final Duration HIGH_INTERVAL = Duration.ofMinutes(15);
    /** RCP NEWS2: MEDIUM, or a single parameter scoring 3 = at least hourly. */
    static final Duration MEDIUM_INTERVAL = Duration.ofHours(1);
    /** RCP NEWS2: a total of 1-4 = at least every 4-6 hours. */
    static final Duration LOW_INTERVAL = Duration.ofHours(4);

    private final AdmissionRepository admissionRepository;
    private final NotificationService notificationService;

    /** The minimum interval to the next set of observations for this NEWS2 result. */
    public static Duration observationInterval(NewsScoreResult score, Duration routine) {
        if (score == null) {
            return routine;
        }
        Duration band = switch (score.riskBand()) {
            case HIGH -> HIGH_INTERVAL;
            case MEDIUM, LOW_MEDIUM -> MEDIUM_INTERVAL;
            case LOW -> score.total() > 0 ? LOW_INTERVAL : routine;
        };
        return band.compareTo(routine) < 0 ? band : routine;
    }

    /** True for the bands the RCP matrix says need an urgent clinical response. */
    public static boolean needsEscalation(NewsScoreResult score) {
        return score != null && (score.riskBand() == RiskBand.HIGH
            || score.riskBand() == RiskBand.MEDIUM || score.riskBand() == RiskBand.LOW_MEDIUM);
    }

    /**
     * Alerts the recording nurse and the responsible physician when the vital
     * set scores MEDIUM or HIGH. Returns the NEWS2 result so a caller can
     * report it.
     */
    public NewsScoreResult escalateIfNeeded(PatientVitalSign vital, UUID patientId, UUID hospitalId,
                                            String recorderUsername) {
        return escalateIfNeeded(vital, patientId, hospitalId, recorderUsername, null);
    }

    /**
     * QA D265: the same escalation for a vital set taken outside a stay — at
     * triage there is no admission, so the clinician who owns the visit
     * ({@code visitPhysicianUsername}, the encounter's provider) is alerted as
     * well as the recorder. Every path that stores a vital set comes through
     * here, so the bands and recipients cannot drift between paths.
     */
    public NewsScoreResult escalateIfNeeded(PatientVitalSign vital, UUID patientId, UUID hospitalId,
                                            String recorderUsername, String visitPhysicianUsername) {
        NewsScoreResult score = NewsScoreCalculator.score(vital);
        // QA D518: an adult NEWS2 band never pages anyone about a child.
        if (!NewsScoreCalculator.applies(vital)
                || !needsEscalation(score) || patientId == null || hospitalId == null) {
            return score;
        }
        Set<String> recipients = new LinkedHashSet<>();
        if (recorderUsername != null && !recorderUsername.isBlank()) {
            recipients.add(recorderUsername);
        }
        responsiblePhysicianUsername(patientId, hospitalId).ifPresent(recipients::add);
        if (visitPhysicianUsername != null && !visitPhysicianUsername.isBlank()) {
            recipients.add(visitPhysicianUsername);
        }
        if (recipients.isEmpty()) {
            return score;
        }
        String patientName = vital.getPatient() != null ? vital.getPatient().getFullName() : null;
        // Read by each recipient in their own language (QA D665), never the
        // recorder's request locale.
        Object[] args = {score.total(), patientName != null ? patientName : "-",
            observationInterval(score, Duration.ofHours(12)).toMinutes()};
        String key = score.riskBand() == RiskBand.HIGH ? "news2.escalation.high" : "news2.escalation.medium";
        for (String recipient : recipients) {
            notificationService.createNotification(key, args, recipient, NOTIFICATION_TYPE);
        }
        return score;
    }

    private Optional<String> responsiblePhysicianUsername(UUID patientId, UUID hospitalId) {
        // QA D1065 class (round 18): this hospital's open stay, not the
        // patient's latest admission anywhere filtered afterwards.
        return admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId,
                com.example.hms.enums.AdmissionStatus.OPEN).stream()
            .max(java.util.Comparator.comparing(com.example.hms.model.Admission::getAdmissionDateTime,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
            .map(News2EscalationService::responsibleOf)
            .filter(s -> s.getUser() != null && s.getUser().getUsername() != null)
            .map(s -> s.getUser().getUsername());
    }

    private static Staff responsibleOf(Admission admission) {
        return admission.getAttendingPhysician() != null
            ? admission.getAttendingPhysician() : admission.getAdmittingProvider();
    }
}
