package com.example.hms.service.support;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;

import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;

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
 */
public final class PatientMeasureRules {

    /** The growth tab's age limit (patient-detail showGrowthTab): curves stop at 19. */
    public static final int HEAD_CIRCUMFERENCE_MAX_AGE_YEARS = 20;

    private PatientMeasureRules() {
    }

    public static boolean isRecordedMale(Patient patient) {
        String gender = patient == null || patient.getGender() == null ? "" : patient.getGender().trim().toUpperCase(Locale.ROOT);
        return "MALE".equals(gender) || "M".equals(gender);
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
