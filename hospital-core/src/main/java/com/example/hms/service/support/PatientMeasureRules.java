package com.example.hms.service.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.utility.ObstetricEligibility;

import java.time.LocalDate;
import java.time.Period;

/**
 * QA D1077 (round 18): a measurement accepted whatever the patient. The
 * triage form offered and stored a fundal height (28 cm) for a man, and the
 * ward vitals offered a head circumference for a 65-year-old. The portal now
 * hides both fields by the same rules (shared/patient-measures.ts); the
 * server refuses them too, so no other client can store them.
 *
 * <ul>
 *   <li>Fundal height: never for a patient recorded as male (unknown sex keeps it).</li>
 *   <li>Head circumference: under 20 years only, the growth tab's own rule
 *       (the WHO/CDC curves stop at 19); unknown date of birth keeps it.</li>
 * </ul>
 *
 * <p>QA D1077 (round 19, NOT_FIXED): the generic {@code POST /patients/{id}/vitals}
 * stored a head circumference for a 67-year-old, because only the nurse
 * station called the rule. Every write of a {@link PatientVitalSign} now goes
 * through {@link #requireVitalSignApplicable} (or, for the nurse station, the
 * single-measure rule) right before it is saved; {@code VitalSignMeasureRuleGuardTest}
 * fails on a save that does not.
 */
public final class PatientMeasureRules {

    /** The growth tab's age limit (patient-detail showGrowthTab): curves stop at 19. */
    public static final int HEAD_CIRCUMFERENCE_MAX_AGE_YEARS = 20;

    private PatientMeasureRules() {
    }

    /**
     * The obstetric records' own male predicate (MALE, M, homme, masculin, H):
     * round 19 found the vitals rule reading only MALE/M, so a patient recorded
     * as "Homme" kept a fundal height the maternity screens would refuse.
     */
    public static boolean isRecordedMale(Patient patient) {
        return ObstetricEligibility.isRecordedMale(patient);
    }

    /**
     * The one check before a vital-sign row is saved: its head circumference
     * against the patient's age, and the fundal height that came with it (the
     * triage form charts it in the row's notes) against the patient's sex.
     */
    public static void requireVitalSignApplicable(PatientVitalSign vital, Number fundalHeightCm, LocalDate today) {
        if (vital == null) {
            return;
        }
        requireFundalHeightApplicable(vital.getPatient(), fundalHeightCm);
        requireHeadCircumferenceApplicable(vital.getPatient(), vital.getHeadCircumferenceCm(), today);
    }

    /** Refuses a fundal height for a patient recorded as male. */
    public static void requireFundalHeightApplicable(Patient patient, Number fundalHeightCm) {
        if (fundalHeightCm != null && isRecordedMale(patient)) {
            throw new BusinessException("vitals.fundalHeight.notApplicable");
        }
    }

    /** Refuses a head circumference for a patient aged 20 or more on {@code today}. */
    public static void requireHeadCircumferenceApplicable(Patient patient, Number headCircumferenceCm, LocalDate today) {
        if (headCircumferenceCm == null || patient == null || patient.getDateOfBirth() == null || today == null) {
            return;
        }
        if (Period.between(patient.getDateOfBirth(), today).getYears() >= HEAD_CIRCUMFERENCE_MAX_AGE_YEARS) {
            throw new BusinessException("vitals.headCircumference.notApplicable");
        }
    }
}
