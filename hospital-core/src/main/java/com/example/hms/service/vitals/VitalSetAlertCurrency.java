package com.example.hms.service.vitals;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.maternity.NewbornAlertRules;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * QA D4103 (r81): whether an urgent alert raised from a vital set still stands, read from the patient's later vital
 * sets at the same hospital. Before, only an adult's HIGH NEWS2 had such a rule: a newborn's danger sign recorded on
 * a vital set, an adult's urgent hypertension and a maternal early warning were escalated to « non résolue » after a
 * later normal reading was on the chart.
 *
 * <p>No number lives here. Each rule asks the rule that raised the alert to judge the later reading:
 * {@link NewbornAlertRules}, {@link SevereHypertension#isAdultSevere} and {@link MaternalEarlyWarning}.
 *
 * <p>The decision is the one an adult's NEWS2 has (QA D3862, review of #1339): it reads recorded times only, never
 * the order rows arrived in, and fails safe.
 * <ul>
 *   <li>Only a later reading that re-measured every value the alert rests on, and that the rule no longer judges
 *       urgent, can close the alert (QA D658: a reading that left a value out says nothing).</li>
 *   <li>It closes the alert only when it is strictly later than the alert and than every urgent reading since,
 *       complete or not. A tie goes to the urgent reading.</li>
 *   <li>A source that cannot be read keeps the alert standing.</li>
 * </ul>
 *
 * <p>PROVISIONAL, part of the rule set URGENT_ALERT_ESCALATION (version 2026-11-27.1): pending the médecin-chef's
 * sign-off like the ladder itself.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.URGENT_ALERT_ESCALATION)
public class VitalSetAlertCurrency {

    /** The later sets read for one alert: far more than a day of quarter-hourly rounds. */
    private static final int LATER_SETS = 200;

    private final PatientVitalSignRepository vitalSignRepository;

    /** The maternal score's rules (setter-injected; absent or switched off, a maternal warning stands). */
    private MaternalEarlyWarningRules maternalRules;

    @Autowired(required = false)
    void setMaternalRules(MaternalEarlyWarningRules maternalRules) {
        this.maternalRules = maternalRules;
    }

    /**
     * A newborn's URGENT finding of {@code alertType} (a {@code NewbornAlertType} name) on the vital set
     * {@code sourceId}: closed by a later set of the baby that re-measured the value of that type and on which the
     * newborn rules raise no URGENT finding of the type. A set recorded once the baby is past the neonatal period
     * is not judged by those rules and says nothing.
     */
    public boolean newbornFindingResolved(UUID sourceId, UUID patientId, UUID hospitalId, String alertType) {
        Optional<PatientVitalSign> source = source(sourceId);
        if (source.isEmpty() || alertType == null || source.get().getPatient() == null) {
            return false;
        }
        LocalDate dateOfBirth = source.get().getPatient().getDateOfBirth();
        if (newbornValues(alertType, source.get()).stream().allMatch(Objects::isNull)) {
            return false;
        }
        return closedBy(source.get().getRecordedAt(), since(source.get(), patientId, hospitalId),
            reading -> urgentNewbornFinding(reading, dateOfBirth, alertType),
            reading -> VitalSignJudgement.isNewborn(dateOfBirth, reading.getRecordedAt())
                && newbornValues(alertType, reading).stream().noneMatch(Objects::isNull));
    }

    /**
     * An adult's urgent hypertension on the vital set {@code sourceId}: closed by a later set that measured both
     * pressures below the adult severe range. A later pressure still in that range keeps it, with or without a
     * ticked sign: the sign is a checkbox, and an unticked box cannot be told from a sign nobody looked for.
     */
    public boolean urgentHypertensionResolved(UUID sourceId, UUID patientId, UUID hospitalId) {
        Optional<PatientVitalSign> source = source(sourceId);
        if (source.isEmpty()) {
            return false;
        }
        return closedBy(source.get().getRecordedAt(), since(source.get(), patientId, hospitalId),
            reading -> SevereHypertension.isAdultSevere(reading.getSystolicBpMmHg(), reading.getDiastolicBpMmHg()),
            reading -> reading.getSystolicBpMmHg() != null && reading.getDiastolicBpMmHg() != null);
    }

    /**
     * A maternal early warning (medium, high or a RED add-on alert) on the vital set {@code sourceId}: closed by a
     * later set with a complete score that asks for no urgent alert. The pulse is scored on both of the score's
     * tables (before and after 48 h from the birth), and the stricter answer is kept, so nothing here depends on
     * reading the obstetric record again. A warning a ticked concern raised stands until somebody acknowledges it:
     * an unticked concern on a later set cannot be told from a concern nobody asked about.
     */
    public boolean maternalWarningResolved(UUID sourceId, UUID patientId, UUID hospitalId) {
        Optional<PatientVitalSign> source = source(sourceId);
        if (source.isEmpty() || maternalRules == null || !maternalRules.isEnabled()
                || !MaternalEarlyWarning.concernsOf(source.get()).isEmpty()) {
            return false;
        }
        return closedBy(source.get().getRecordedAt(), since(source.get(), patientId, hospitalId),
            this::maternalUrgent, this::maternalComplete);
    }

    private boolean maternalUrgent(PatientVitalSign reading) {
        return MaternalEarlyWarningService.alertTypeOf(MaternalEarlyWarning.score(maternalRules, reading, false)) != null
            || MaternalEarlyWarningService.alertTypeOf(MaternalEarlyWarning.score(maternalRules, reading, true)) != null;
    }

    private boolean maternalComplete(PatientVitalSign reading) {
        return MaternalEarlyWarning.score(maternalRules, reading, false).complete()
            && MaternalEarlyWarning.score(maternalRules, reading, true).complete();
    }

    private Optional<PatientVitalSign> source(UUID sourceId) {
        return sourceId == null ? Optional.empty()
            : vitalSignRepository.findById(sourceId).filter(vital -> vital.getRecordedAt() != null);
    }

    /** The patient's sets at the hospital recorded at or after the source, the source itself left out. */
    private List<PatientVitalSign> since(PatientVitalSign source, UUID patientId, UUID hospitalId) {
        return vitalSignRepository.findWithinRange(patientId, hospitalId, source.getRecordedAt(), null,
                PageRequest.of(0, LATER_SETS))
            .stream()
            .filter(later -> !Objects.equals(later.getId(), source.getId()))
            .filter(later -> later.getRecordedAt() != null && !later.getRecordedAt().isBefore(source.getRecordedAt()))
            .toList();
    }

    /**
     * Whether a reading that {@code remeasured} the alert's values and is not {@code urgent} is strictly later than
     * the alert and than every urgent reading in {@code since}. Order-free: only recorded times are compared.
     */
    static boolean closedBy(LocalDateTime alertAt, List<PatientVitalSign> since, Predicate<PatientVitalSign> urgent,
                            Predicate<PatientVitalSign> remeasured) {
        LocalDateTime latestUrgent = alertAt;
        LocalDateTime latestClear = null;
        for (PatientVitalSign reading : since) {
            LocalDateTime at = reading.getRecordedAt();
            if (urgent.test(reading)) {
                latestUrgent = at.isAfter(latestUrgent) ? at : latestUrgent;
            } else if (remeasured.test(reading) && (latestClear == null || at.isAfter(latestClear))) {
                latestClear = at;
            }
        }
        return latestClear != null && latestClear.isAfter(latestUrgent);
    }

    /** Whether the newborn rules raise an URGENT finding of {@code alertType} on the reading. */
    static boolean urgentNewbornFinding(PatientVitalSign reading, LocalDate dateOfBirth, String alertType) {
        NewbornAlertRules.Reading judged = new NewbornAlertRules.Reading(reading.getTemperatureCelsius(),
            reading.getHeartRateBpm(), reading.getRespiratoryRateBpm(), reading.getSpo2Percent(),
            reading.getBloodGlucoseMgDl(), null, reading.getSystolicBpMmHg(), reading.getDiastolicBpMmHg(),
            NewbornAlertRules.hoursSinceBirth(null, dateOfBirth, reading.getRecordedAt()), false, false);
        return NewbornAlertRules.evaluate(judged).stream()
            .anyMatch(finding -> finding.severity() == NewbornAlertSeverity.URGENT
                && finding.type() != null && finding.type().name().equals(alertType));
    }

    /** The value the newborn rules read for each URGENT finding type of a vital set; none for any other type. */
    static List<Object> newbornValues(String alertType, PatientVitalSign reading) {
        return switch (alertType) {
            case "THERMOREGULATION" -> Arrays.asList(reading.getTemperatureCelsius());
            case "CARDIOVASCULAR" -> Arrays.asList(reading.getHeartRateBpm());
            case "RESPIRATORY" -> Arrays.asList(reading.getRespiratoryRateBpm());
            case "OXYGENATION" -> Arrays.asList(reading.getSpo2Percent());
            case "GLUCOSE" -> Arrays.asList(reading.getBloodGlucoseMgDl());
            default -> Arrays.asList((Object) null);
        };
    }
}
