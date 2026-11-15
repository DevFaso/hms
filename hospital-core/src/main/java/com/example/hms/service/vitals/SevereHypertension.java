package com.example.hms.service.vitals;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.utility.ObstetricEligibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.UUID;

/**
 * The one severe-blood-pressure rule (QA D1890, extended in r54). Every surface asks it: the
 * midwife's postpartum observation, every vitals write (nurse station, triage, the vitals API) and
 * the doctor's BPA panel. No other source compares a blood pressure with these numbers
 * ({@code SevereHypertensionGuardTest}).
 *
 * <p><b>Obstetric rule</b> (pregnancy and the 42 days after a birth): systolic &ge;
 * {@value #OBSTETRIC_SYSTOLIC} OR diastolic &ge; {@value #OBSTETRIC_DIASTOLIC} mmHg (inclusive, either
 * suffices) &rarr; URGENT, obstetric review. Under it when, at the reading:
 * <ul>
 *   <li><b>postpartum</b>: a delivery is recorded for this mother (any hospital) on the reading's day
 *       or up to {@value #POSTPARTUM_DAYS} days before it (ACOG: the six weeks after birth); day 42
 *       is still postpartum, day 43 is not;</li>
 *   <li><b>pregnant</b>: she is in an active labour episode (any hospital), or her current maternal
 *       history (latest version) dates a pregnancy that covers the reading's day, as the pregnancy
 *       advisory reads it: from due date &minus; 280 days to due date + 14 days, the due date being
 *       the ultrasound one, else the recorded one, else LMP + 280 days; and no birth is recorded
 *       between the pregnancy's start and the reading.</li>
 * </ul>
 * A patient recorded as male is never under the obstetric rule.
 *
 * <p><b>Adult rule</b> (18 years or more at the reading, or an unknown birth date, and not under the
 * obstetric rule): systolic &ge; {@value #ADULT_SYSTOLIC} OR diastolic &ge; {@value #ADULT_DIASTOLIC}
 * mmHg (inclusive) &rarr; a WARNING asking to recheck at rest and look for organ damage; URGENT only
 * when the recorder ticked a sign of organ damage. Under 18 there is no rule here.
 *
 * <p>Nothing here refuses a reading: the value is saved first, acknowledging is never required, and
 * the first reading warns (no confirmation delay); the warning text asks for the recheck.
 *
 * <p>PROVISIONAL (user decisions of 2026-11-14 and 2026-11-15; sources: ACOG CO 692, ACOG's six-week
 * postpartum guidance, ACC/AHA 2017/2025, ESH 2023 grade 3), pending the obstetric and clinical leads'
 * approval. See docs/qa/approvals-register.md and docs/qa/regles-cliniques-provisoires.md before
 * changing a number.
 */
@Component
public class SevereHypertension {

    /** Obstetric severe range, systolic (mmHg, inclusive). */
    public static final int OBSTETRIC_SYSTOLIC = 160;
    /** Obstetric severe range, diastolic (mmHg, inclusive). */
    public static final int OBSTETRIC_DIASTOLIC = 110;
    /** Adult severe range, systolic (mmHg, inclusive). */
    public static final int ADULT_SYSTOLIC = 180;
    /** Adult severe range, diastolic (mmHg, inclusive). */
    public static final int ADULT_DIASTOLIC = 120;
    /** The postpartum period in days after a recorded birth (ACOG: six weeks). */
    public static final int POSTPARTUM_DAYS = 42;
    /** The adult rule's first age in whole years. */
    public static final int ADULT_FROM_YEARS = 18;
    /** Naegele: a due date is 280 days after the last menstrual period. */
    public static final int PREGNANCY_DAYS = 280;
    /** Post-term is 42 weeks, two weeks past the due date; beyond it the pregnancy is not assumed ongoing. */
    public static final int POST_TERM_GRACE_DAYS = 14;

    /** The postpartum alert code and sentence (unchanged since D1890: stored on postpartum alerts). */
    public static final String ALERT_CODE = "postpartum-severe-hypertension";
    public static final String MESSAGE_KEY = "postpartum.alert.msg.postpartum-severe-hypertension";
    /** The pregnancy alert code and sentence. */
    public static final String PREGNANCY_ALERT_CODE = "pregnancy-severe-hypertension";
    public static final String PREGNANCY_MESSAGE_KEY = "vitals.alert.msg.pregnancy-severe-hypertension";
    /** The adult alert code and its two sentences. */
    public static final String ADULT_ALERT_CODE = "adult-severe-hypertension";
    public static final String ADULT_MESSAGE_KEY = "vitals.alert.msg.adult-severe-hypertension";
    public static final String ADULT_URGENT_MESSAGE_KEY = "vitals.alert.msg.adult-severe-hypertension-urgent";

    /** Which rule a patient is under at a reading. */
    public enum Scope { PREGNANT, POSTPARTUM, ADULT, NONE }

    /** What the rule says. */
    public enum Level { NONE, WARNING, URGENT }

    /** The verdict on one reading: the rule it was judged by and what that rule says. */
    public record Verdict(Scope scope, Level level) {
        public static final Verdict NONE = new Verdict(Scope.NONE, Level.NONE);

        public boolean raised() {
            return level != Level.NONE;
        }

        public boolean obstetric() {
            return raised() && (scope == Scope.PREGNANT || scope == Scope.POSTPARTUM);
        }

        public boolean adult() {
            return raised() && scope == Scope.ADULT;
        }

        public String alertCode() {
            return switch (scope) {
                case POSTPARTUM -> ALERT_CODE;
                case PREGNANT -> PREGNANCY_ALERT_CODE;
                case ADULT -> ADULT_ALERT_CODE;
                case NONE -> null;
            };
        }

        public String messageKey() {
            return switch (scope) {
                case POSTPARTUM -> MESSAGE_KEY;
                case PREGNANT -> PREGNANCY_MESSAGE_KEY;
                case ADULT -> level == Level.URGENT ? ADULT_URGENT_MESSAGE_KEY : ADULT_MESSAGE_KEY;
                case NONE -> null;
            };
        }
    }

    /** One blood-pressure reading: when it was taken, when it reached the chart, and any organ-damage sign. */
    public record Reading(Integer systolic, Integer diastolic, LocalDateTime takenAt, LocalDateTime enteredAt,
                          boolean organDamageSign) {
        public Reading(Integer systolic, Integer diastolic, LocalDateTime takenAt, LocalDateTime enteredAt) {
            this(systolic, diastolic, takenAt, enteredAt, false);
        }
    }

    private final DeliveryRecordRepository deliveryRecordRepository;
    private final MaternalHistoryRepository maternalHistoryRepository;
    private final LaborEpisodeRepository laborEpisodeRepository;

    /** Postpartum knowledge only: no pregnancy is ever read (tests and rules built outside Spring). */
    public SevereHypertension(DeliveryRecordRepository deliveryRecordRepository) {
        this(deliveryRecordRepository, null, null);
    }

    @Autowired
    public SevereHypertension(DeliveryRecordRepository deliveryRecordRepository,
                              MaternalHistoryRepository maternalHistoryRepository,
                              LaborEpisodeRepository laborEpisodeRepository) {
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.maternalHistoryRepository = maternalHistoryRepository;
        this.laborEpisodeRepository = laborEpisodeRepository;
    }

    /** The obstetric thresholds alone: systolic &ge; 160 OR diastolic &ge; 110 mmHg. A missing value never triggers. */
    public static boolean isObstetricSevere(Integer systolic, Integer diastolic) {
        return atOrAbove(systolic, OBSTETRIC_SYSTOLIC) || atOrAbove(diastolic, OBSTETRIC_DIASTOLIC);
    }

    /** The adult thresholds alone: systolic &ge; 180 OR diastolic &ge; 120 mmHg. A missing value never triggers. */
    public static boolean isAdultSevere(Integer systolic, Integer diastolic) {
        return atOrAbove(systolic, ADULT_SYSTOLIC) || atOrAbove(diastolic, ADULT_DIASTOLIC);
    }

    private static boolean atOrAbove(Integer value, int threshold) {
        return value != null && value >= threshold;
    }

    /** The rule on its own, once the scope is known. No query. */
    public static Verdict judge(Scope scope, Integer systolic, Integer diastolic, boolean organDamageSign) {
        if (scope == null) {
            return Verdict.NONE;
        }
        return switch (scope) {
            case PREGNANT, POSTPARTUM -> isObstetricSevere(systolic, diastolic)
                ? new Verdict(scope, Level.URGENT) : Verdict.NONE;
            case ADULT -> isAdultSevere(systolic, diastolic)
                ? new Verdict(scope, organDamageSign ? Level.URGENT : Level.WARNING) : Verdict.NONE;
            case NONE -> Verdict.NONE;
        };
    }

    /**
     * The whole rule for one reading. Queries the record only for a reading in the obstetric severe
     * range (every adult severe reading is in it), so a normal reading costs nothing.
     */
    public Verdict evaluate(Patient patient, Integer systolic, Integer diastolic, LocalDateTime takenAt,
                            boolean organDamageSign) {
        if (patient == null || !isObstetricSevere(systolic, diastolic)) {
            return Verdict.NONE;
        }
        LocalDateTime at = takenAt != null ? takenAt : LocalDateTime.now();
        return judge(scopeOf(patient, at), systolic, diastolic, organDamageSign);
    }

    /** The reading's verdict. */
    public Verdict evaluate(Patient patient, Reading reading) {
        return reading == null ? Verdict.NONE
            : evaluate(patient, reading.systolic(), reading.diastolic(), reading.takenAt(), reading.organDamageSign());
    }

    /** Which rule the patient is under at {@code at}: obstetric first, then adult, else none. */
    public Scope scopeOf(Patient patient, LocalDateTime at) {
        if (patient == null || at == null) {
            return Scope.NONE;
        }
        UUID patientId = patient.getId();
        if (patientId != null && !ObstetricEligibility.isRecordedMale(patient)) {
            if (isPostpartum(patientId, at)) {
                return Scope.POSTPARTUM;
            }
            if (isPregnant(patientId, at)) {
                return Scope.PREGNANT;
            }
        }
        return isAdult(patient.getDateOfBirth(), at) ? Scope.ADULT : Scope.NONE;
    }

    /** 18 years or more on the reading's day; an unknown birth date reads as adult. */
    public static boolean isAdult(LocalDate dateOfBirth, LocalDateTime at) {
        if (dateOfBirth == null || at == null) {
            return true;
        }
        return Period.between(dateOfBirth, at.toLocalDate()).getYears() >= ADULT_FROM_YEARS;
    }

    /** A birth is recorded for this mother on the reading's day or up to 42 days before it. */
    public boolean isPostpartum(UUID patientId, LocalDateTime at) {
        if (patientId == null || at == null || deliveryRecordRepository == null) {
            return false;
        }
        return deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeBetween(
            patientId, at.toLocalDate().minusDays(POSTPARTUM_DAYS).atStartOfDay(), at);
    }

    /** In active labour, or a current maternal history whose pregnancy covers the reading's day, unended by a birth. */
    public boolean isPregnant(UUID patientId, LocalDateTime at) {
        if (patientId == null || at == null) {
            return false;
        }
        if (laborEpisodeRepository != null && laborEpisodeRepository.existsByPatient_IdAndStatus(patientId, LaborStatus.ACTIVE)) {
            return true;
        }
        if (maternalHistoryRepository == null) {
            return false;
        }
        LocalDate dueDate = maternalHistoryRepository.findCurrentByPatientId(patientId)
            .map(SevereHypertension::dueDateOf).orElse(null);
        if (!isPregnantOn(dueDate, at.toLocalDate())) {
            return false;
        }
        return deliveryRecordRepository == null || !deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeBetween(
            patientId, dueDate.minusDays(PREGNANCY_DAYS).atStartOfDay(), at);
    }

    /** The pregnancy a due date dates covers {@code day}: from due &minus; 280 days to due + 14 days. */
    public static boolean isPregnantOn(LocalDate dueDate, LocalDate day) {
        if (dueDate == null || day == null) return false;
        return !day.isBefore(dueDate.minusDays(PREGNANCY_DAYS))
            && !day.isAfter(dueDate.plusDays(POST_TERM_GRACE_DAYS));
    }

    /** The due date a maternal history records: ultrasound first, then the recorded one, then LMP + 280 days. */
    public static LocalDate dueDateOf(MaternalHistory history) {
        if (history == null) return null;
        if (history.getEstimatedDueDateByUltrasound() != null) return history.getEstimatedDueDateByUltrasound();
        if (history.getEstimatedDueDate() != null) return history.getEstimatedDueDate();
        return history.getLastMenstrualPeriod() == null ? null
            : history.getLastMenstrualPeriod().plusDays(PREGNANCY_DAYS);
    }
}
