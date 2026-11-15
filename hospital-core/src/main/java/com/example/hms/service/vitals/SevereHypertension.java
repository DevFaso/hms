package com.example.hms.service.vitals;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.Patient;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.utility.ObstetricEligibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *   <li><b>postpartum</b>: a delivery is recorded for this mother on the reading's day or up to
 *       {@value #POSTPARTUM_DAYS} days before it (ACOG: the six weeks after birth); day 42 is still
 *       postpartum, day 43 is not;</li>
 *   <li><b>pregnant</b>: she is in an active labour episode, or her current maternal
 *       history (latest version) dates a pregnancy that covers the reading's day, as the pregnancy
 *       advisory reads it: from due date &minus; 280 days to due date + 14 days, the due date being
 *       the ultrasound one, else the recorded one, else LMP + 280 days; and no birth is recorded
 *       between the pregnancy's start and the reading.</li>
 * </ul>
 * A patient recorded as male is never under the obstetric rule. Every obstetric record is read only
 * at the hospitals the record-access policy lets the current reader see
 * ({@code RecordAccessPolicy.readableHospitalIds}: never past a sharing opt-out); a raised warning
 * resting on another hospital's record is accounted in the reach ledger ({@code RECORD_SHARE}).
 *
 * <p><b>Adult rule</b> (18 years or more at the reading, an unknown birth date, or, whatever her age,
 * a patient with a readable recorded birth (r54 review, provisional); and not under the obstetric
 * rule): systolic &ge; {@value #ADULT_SYSTOLIC} OR diastolic &ge; {@value #ADULT_DIASTOLIC} mmHg
 * (inclusive) &rarr; a WARNING asking to recheck at rest and look for organ damage; URGENT only when
 * the recorder ticked a sign of organ damage. Under 18 with no birth there is no rule here.
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

    /** The disclosure a foreign record behind a raised warning is accounted under (E9 reach ledger). */
    public static final String REACH_DESCRIPTION =
        "Cross-hospital obstetric record read on the treatment relationship";
    /** The earliest birth "has given birth" looks back to. */
    private static final LocalDateTime ANY_BIRTH_FROM = LocalDateTime.of(1900, 1, 1, 0, 0);

    private final DeliveryRecordRepository deliveryRecordRepository;
    private final MaternalHistoryRepository maternalHistoryRepository;
    private final LaborEpisodeRepository laborEpisodeRepository;
    /** Which hospitals' obstetric records the reader may see: never another hospital's past the policy. */
    private final RecordAccessPolicy recordAccessPolicy;
    /** Accounts for a foreign record behind a raised warning, as every cross-hospital read does. */
    private final CrossHospitalReachRecorder reachRecorder;

    @Autowired
    public SevereHypertension(DeliveryRecordRepository deliveryRecordRepository,
                              MaternalHistoryRepository maternalHistoryRepository,
                              LaborEpisodeRepository laborEpisodeRepository,
                              RecordAccessPolicy recordAccessPolicy,
                              CrossHospitalReachRecorder reachRecorder) {
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.maternalHistoryRepository = maternalHistoryRepository;
        this.laborEpisodeRepository = laborEpisodeRepository;
        this.recordAccessPolicy = recordAccessPolicy;
        this.reachRecorder = reachRecorder;
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
            case ADULT -> isAdultSevere(systolic, diastolic) ? new Verdict(scope, adultLevel(organDamageSign)) : Verdict.NONE;
            case NONE -> Verdict.NONE;
        };
    }

    /** The adult level: URGENT with a ticked sign of organ damage, else a WARNING. */
    private static Level adultLevel(boolean organDamageSign) {
        return organDamageSign ? Level.URGENT : Level.WARNING;
    }

    /** The scope, and the hospital whose record decided it (null when no record did). */
    private record Found(Scope scope, UUID sourceHospitalId) {
    }

    /**
     * The whole rule for one reading taken at {@code actingHospitalId}. Queries the record only for a
     * reading in the obstetric severe range (every adult severe reading is in it), so a normal reading
     * costs nothing. Obstetric records are read only at the hospitals the record-access policy lets
     * the current reader see (a pregnancy the patient withheld from this hospital stays hidden, and
     * she is then judged by the adult rule); a raised warning that rests on another hospital's record
     * is accounted in the reach ledger.
     */
    public Verdict evaluate(Patient patient, UUID actingHospitalId, Integer systolic, Integer diastolic,
                            LocalDateTime takenAt, boolean organDamageSign) {
        if (patient == null || !isObstetricSevere(systolic, diastolic)) {
            return Verdict.NONE;
        }
        LocalDateTime at = takenAt != null ? takenAt : LocalDateTime.now(java.time.ZoneId.systemDefault());
        Found found = find(patient, actingHospitalId, at);
        Verdict verdict = judge(found.scope(), systolic, diastolic, organDamageSign);
        if (verdict.raised()) {
            recordForeignReach(patient.getId(), actingHospitalId, found.sourceHospitalId());
        }
        return verdict;
    }

    /** The reading's verdict, read at {@code actingHospitalId}. */
    public Verdict evaluate(Patient patient, UUID actingHospitalId, Reading reading) {
        return reading == null ? Verdict.NONE
            : evaluate(patient, actingHospitalId, reading.systolic(), reading.diastolic(), reading.takenAt(),
                reading.organDamageSign());
    }

    /** Which rule the patient is under at {@code at}, read at {@code actingHospitalId}. */
    public Scope scopeOf(Patient patient, UUID actingHospitalId, LocalDateTime at) {
        return patient == null || at == null ? Scope.NONE : find(patient, actingHospitalId, at).scope();
    }

    /**
     * Obstetric first (postpartum, then pregnant), then adult: 18 or more, an unknown birth date, or
     * (r54 review, provisional) a patient who has given birth, whatever her age, once past the 42
     * days. Under 18 with no birth on record: no rule.
     */
    private Found find(Patient patient, UUID actingHospitalId, LocalDateTime at) {
        UUID patientId = patient.getId();
        boolean obstetricEligible = patientId != null && !ObstetricEligibility.isRecordedMale(patient);
        Set<UUID> readable = obstetricEligible ? readableHospitals(patientId, actingHospitalId) : Set.of();
        if (!readable.isEmpty()) {
            Optional<DeliveryRecord> recentBirth = latestBirth(patientId, readable,
                at.toLocalDate().minusDays(POSTPARTUM_DAYS).atStartOfDay(), at);
            if (recentBirth.isPresent()) {
                return new Found(Scope.POSTPARTUM, hospitalOf(recentBirth.get().getHospital()));
            }
            Optional<UUID> pregnancySource = pregnancySource(patientId, readable, at);
            if (pregnancySource.isPresent()) {
                return new Found(Scope.PREGNANT, pregnancySource.get());
            }
        }
        if (isAdult(patient.getDateOfBirth(), at)) {
            return new Found(Scope.ADULT, null);
        }
        if (!readable.isEmpty()) {
            Optional<DeliveryRecord> anyBirth = latestBirth(patientId, readable, ANY_BIRTH_FROM, at);
            if (anyBirth.isPresent()) {
                return new Found(Scope.ADULT, hospitalOf(anyBirth.get().getHospital()));
            }
        }
        return new Found(Scope.NONE, null);
    }

    /** 18 years or more on the reading's day; an unknown birth date reads as adult. */
    public static boolean isAdult(LocalDate dateOfBirth, LocalDateTime at) {
        if (dateOfBirth == null || at == null) {
            return true;
        }
        return Period.between(dateOfBirth, at.toLocalDate()).getYears() >= ADULT_FROM_YEARS;
    }

    /**
     * The hospitals whose obstetric records the current reader may see for this patient: the
     * record-access policy's answer (the acting hospital, plus others on a permitted treatment
     * relationship, none past a sharing opt-out). Without an acting hospital, none.
     */
    private Set<UUID> readableHospitals(UUID patientId, UUID actingHospitalId) {
        if (actingHospitalId == null) {
            return Set.of();
        }
        if (recordAccessPolicy == null) {
            return Set.of(actingHospitalId);
        }
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(currentUserId(), patientId, actingHospitalId);
        return readable == null ? Set.of() : readable;
    }

    private static UUID currentUserId() {
        return HospitalContextHolder.getContext().map(HospitalContext::getPrincipalUserId).orElse(null);
    }

    private Optional<DeliveryRecord> latestBirth(UUID patientId, Set<UUID> readable, LocalDateTime from,
                                                 LocalDateTime to) {
        if (deliveryRecordRepository == null) {
            return Optional.empty();
        }
        return deliveryRecordRepository
            .findFirstByPatient_IdAndHospital_IdInAndBirthDateTimeBetweenOrderByBirthDateTimeDesc(patientId, readable,
                from, to);
    }

    /**
     * The hospital of the record that makes her pregnant at {@code at}: an active labour episode, or
     * the current maternal history (latest version among the readable hospitals) whose pregnancy
     * covers the reading's day with no birth recorded since its start.
     */
    private Optional<UUID> pregnancySource(UUID patientId, Set<UUID> readable, LocalDateTime at) {
        if (laborEpisodeRepository != null) {
            Optional<LaborEpisode> labour = laborEpisodeRepository
                .findFirstByPatient_IdAndHospital_IdInAndStatusOrderByAdmittedAtDesc(patientId, readable,
                    LaborStatus.ACTIVE);
            if (labour.isPresent()) {
                return Optional.of(hospitalOf(labour.get().getHospital()));
            }
        }
        if (maternalHistoryRepository == null) {
            return Optional.empty();
        }
        Optional<MaternalHistory> history = maternalHistoryRepository
            .findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(patientId, readable);
        LocalDate dueDate = history.map(SevereHypertension::dueDateOf).orElse(null);
        if (!isPregnantOn(dueDate, at.toLocalDate())
            || latestBirth(patientId, readable, dueDate.minusDays(PREGNANCY_DAYS).atStartOfDay(), at).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(hospitalOf(history.get().getHospital()));
    }

    private static UUID hospitalOf(com.example.hms.model.Hospital hospital) {
        return CrossHospitalReachRecorder.hospitalIdOf(hospital);
    }

    /** One RECORD_SHARE row when the warning rests on another hospital's record. Never throws. */
    private void recordForeignReach(UUID patientId, UUID actingHospitalId, UUID sourceHospitalId) {
        if (reachRecorder == null || sourceHospitalId == null || sourceHospitalId.equals(actingHospitalId)) {
            return;
        }
        reachRecorder.recordReach(patientId, actingHospitalId, currentUserId(), null,
            CrossHospitalReachRecorder.reachOf(List.of(sourceHospitalId), actingHospitalId), REACH_DESCRIPTION);
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
