package com.example.hms.service.support;

import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.utility.ObstetricEligibility;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * QA D1490 (round 36): the death record accepted impossible facts, and the flags feed the WHO indicators.
 *
 * <p>User ruling: a maternal or perinatal CLASSIFICATION never stops a death from being recorded. With the
 * classification left off, a death always saves, whatever the patient's age or sex. Only the classification is
 * refused, on its own field, with a reason saying the death can be recorded without it. A date before the birth
 * is a wrong date, and is refused on the date. Unknown sex or birth date refuses nothing.
 *
 * <p>A record here marks THIS patient deceased, so a stillbirth cannot be recorded on the mother's chart: it would
 * close her stays and refuse her care. Her stillbirth belongs on the delivery record (LaborOutcome.STILLBIRTH).
 */
public final class DeathClassificationRules {

    /**
     * The end of the perinatal period: seven completed days after birth. WHO defines the perinatal period as from
     * 22 completed weeks of gestation through days 0-6 after birth. The 22-week start is not enforced: gestational
     * age is not captured on this record.
     *
     * <p>NOT CLINICALLY APPROVED: this boundary awaits approval by the obstetric/neonatal lead and the responsible
     * coding authority.
     */
    public static final int PERINATAL_PERIOD_COMPLETED_DAYS = 7;

    /**
     * The end of {@link PerinatalDeathType#LATE_NEONATAL} as that enum defines it ("between 7 and 28 days of life").
     * A late neonatal death lies past the WHO perinatal period yet this register files it under perinatal; holding
     * it to seven days would make the type impossible. Awaits the same approval.
     */
    public static final int LATE_NEONATAL_COMPLETED_DAYS = 28;

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

    /** Refused on the type's field when the patient, at death, is past what that type can mean. */
    public static void requirePerinatalPossible(Patient patient, PerinatalDeathType type, LocalDateTime diedAt) {
        LocalDate born = patient.getDateOfBirth();
        if (born == null || diedAt == null || type == null) {
            return;
        }
        int bound = type == PerinatalDeathType.LATE_NEONATAL
            ? LATE_NEONATAL_COMPLETED_DAYS : PERINATAL_PERIOD_COMPLETED_DAYS;
        if (ChronoUnit.DAYS.between(born, diedAt.toLocalDate()) >= bound) {
            throw new FieldBusinessException("perinatalType", "mortality.perinatal.pastPeriod", bound);
        }
    }
}
