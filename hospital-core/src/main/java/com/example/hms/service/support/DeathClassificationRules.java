package com.example.hms.service.support;

import com.example.hms.enums.PerinatalAgeBasis;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.utility.ObstetricEligibility;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * QA D1490 (round 36), CR-07 and CR-08: the death record accepted impossible facts, and the flags feed the WHO
 * indicators.
 *
 * <p>User ruling: a maternal or perinatal CLASSIFICATION never stops a death from being recorded. With the
 * classification left off, a death always saves, whatever the patient's age or sex. Only the classification or a
 * fact offered for it is refused, on its own field, with a reason saying the death can be recorded without it. A
 * date before the birth is a wrong date, and is refused on the date. Unknown sex or birth date refuses nothing.
 *
 * <p>The clinician selects the classification. These rules check it against the facts and never replace it.
 *
 * <p>A record here marks THIS patient deceased, so a stillbirth cannot be recorded on the mother's chart: it would
 * close her stays and refuse her care. Her stillbirth belongs on the delivery record (LaborOutcome.STILLBIRTH).
 */
public final class DeathClassificationRules {

    /**
     * The end of the early neonatal period, and of the perinatal period: seven completed days after birth. WHO defines
     * the perinatal period as from 22 completed weeks of gestation through days 0-6 after birth.
     *
     * <p>NOT CLINICALLY APPROVED: this boundary awaits approval by the obstetric/neonatal lead and the responsible
     * coding authority.
     */
    public static final int PERINATAL_PERIOD_COMPLETED_DAYS = 7;

    /**
     * The end of {@link PerinatalDeathType#LATE_NEONATAL}: 28 completed days, days 7-27. A late neonatal death lies
     * past the WHO perinatal period: this register holds it, and reports it apart from the perinatal figure. Awaits
     * the same approval.
     */
    public static final int LATE_NEONATAL_COMPLETED_DAYS = 28;

    /**
     * A date of birth is a wall-clock date and a birth time is stored on the server's clock, so the two can sit a day
     * apart without being wrong.
     */
    private static final int BIRTH_DAY_TOLERANCE = 1;

    private DeathClassificationRules() {
    }

    /** A death before the birth is a wrong date. */
    public static void requireDiedAfterBirth(Patient patient, LocalDateTime diedAt) {
        LocalDate born = patient.getDateOfBirth();
        if (born != null && diedAt != null && diedAt.toLocalDate().isBefore(born)) {
            throw new FieldBusinessException("diedAt", "mortality.diedBeforeBirth");
        }
    }

    /** Refused on the flag's own field; the death itself is not what is refused. */
    public static void requireMaternalPossible(Patient patient) {
        if (ObstetricEligibility.isRecordedMale(patient)) {
            throw new FieldBusinessException("maternalDeath", "mortality.maternal.recordedMale");
        }
    }

    /** A fact offered for the classification, refused on its own field. Left empty, it refuses nothing. */
    public static void requireBirthFactsPossible(Patient patient, LocalDateTime bornAt, Integer gestationWeeks,
                                                 LocalDateTime diedAt) {
        if (gestationWeeks != null && !isPlausibleGestation(gestationWeeks)) {
            throw new FieldBusinessException("gestationalAgeWeeks", "mortality.perinatal.gestationRange");
        }
        if (bornAt != null && !birthTimeFits(patient, bornAt, diedAt)) {
            throw new FieldBusinessException("bornAt", "mortality.perinatal.bornAtImpossible");
        }
    }

    public static boolean isPlausibleGestation(int weeks) {
        return weeks >= PerinatalDeathType.MIN_GESTATION_WEEKS && weeks <= PerinatalDeathType.MAX_GESTATION_WEEKS;
    }

    /** Whether a birth time can be this patient's: not after the death, and on the date of birth if one is held. */
    public static boolean birthTimeFits(Patient patient, LocalDateTime bornAt, LocalDateTime diedAt) {
        if (diedAt != null && bornAt.isAfter(diedAt)) {
            return false;
        }
        LocalDate born = patient.getDateOfBirth();
        return born == null || Math.abs(ChronoUnit.DAYS.between(born, bornAt.toLocalDate())) <= BIRTH_DAY_TOLERANCE;
    }

    /**
     * Refused on the type's field when the facts cannot support the type. Elapsed time decides when a birth time is
     * known. With only dates the birth could be at any hour of that day, so the completed days are one of two values:
     * the type is refused only when both rule it out, and a boundary the dates cannot decide is kept and said to be so.
     *
     * @return what the age at death rests on; {@code UNKNOWN} with no date of birth, which is not a confirmation
     */
    public static PerinatalAgeBasis requirePerinatalPossible(Patient patient, PerinatalDeathType type,
                                                             LocalDateTime diedAt, LocalDateTime bornAt,
                                                             PerinatalBirthStatus birthStatus) {
        requireBirthStatusFits(type, birthStatus);
        CompletedDays age = completedDays(patient.getDateOfBirth(), bornAt, diedAt);
        if (age == null) {
            return PerinatalAgeBasis.UNKNOWN;
        }
        int lowest = type == PerinatalDeathType.LATE_NEONATAL ? PERINATAL_PERIOD_COMPLETED_DAYS : 0;
        int bound = type == PerinatalDeathType.LATE_NEONATAL
            ? LATE_NEONATAL_COMPLETED_DAYS : PERINATAL_PERIOD_COMPLETED_DAYS;
        if (age.most() < lowest) {
            throw new FieldBusinessException("perinatalType", "mortality.perinatal.beforeLateNeonatal", lowest);
        }
        if (age.least() >= bound) {
            throw new FieldBusinessException("perinatalType", "mortality.perinatal.pastPeriod", bound);
        }
        if (bornAt != null) {
            return PerinatalAgeBasis.ELAPSED_TIME;
        }
        boolean undecided = age.least() < lowest || age.most() >= bound;
        return undecided ? PerinatalAgeBasis.DATE_ONLY_BOUNDARY : PerinatalAgeBasis.DATE_ONLY;
    }

    private static void requireBirthStatusFits(PerinatalDeathType type, PerinatalBirthStatus birthStatus) {
        if (type == PerinatalDeathType.STILLBIRTH && birthStatus == PerinatalBirthStatus.LIVE_BIRTH) {
            throw new FieldBusinessException("perinatalType", "mortality.perinatal.stillbirthLiveBorn");
        }
        if (type != PerinatalDeathType.STILLBIRTH && birthStatus == PerinatalBirthStatus.STILLBIRTH) {
            throw new FieldBusinessException("perinatalType", "mortality.perinatal.neonatalStillborn");
        }
    }

    /** Null when no date or time of birth is on record: an unknown age is not a young one. */
    private static CompletedDays completedDays(LocalDate bornOn, LocalDateTime bornAt, LocalDateTime diedAt) {
        if (diedAt == null) {
            return null;
        }
        if (bornAt != null) {
            long elapsed = ChronoUnit.DAYS.between(bornAt, diedAt);
            return new CompletedDays(elapsed, elapsed);
        }
        if (bornOn == null) {
            return null;
        }
        long calendar = ChronoUnit.DAYS.between(bornOn, diedAt.toLocalDate());
        return new CompletedDays(Math.max(calendar - 1, 0), calendar);
    }

    private record CompletedDays(long least, long most) {
    }
}
