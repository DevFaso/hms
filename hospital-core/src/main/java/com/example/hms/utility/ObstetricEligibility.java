package com.example.hms.utility;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Patient;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses obstetric records (labour episode, postpartum observation, prenatal
 * schedule, birth plan, high-risk pregnancy plan) for a patient whose record
 * says male (QA D46: a labour episode and postpartum observations were saved
 * for a MALE patient).
 *
 * <p>{@code Patient.gender} is free text with no closed vocabulary, so this
 * refuses only the values that unambiguously mean male. A blank, unknown or
 * other value is let through: the record is then incomplete rather than
 * contradictory, and blocking care on a missing field is the worse failure.
 * When a male value is wrong, the fix is to correct the patient record, which
 * then unblocks these screens.
 */
public final class ObstetricEligibility {

    public static final String MESSAGE_KEY = "maternity.patient.male";

    /** QA D413 (D46 regression): a labour episode was accepted for a 5-year-old. */
    public static final String TOO_YOUNG_MESSAGE_KEY = "maternity.patient.tooYoung";

    /**
     * Youngest recorded age at which an obstetric record is accepted. Set well
     * below any plausible pregnancy so real care is never blocked; a child's
     * chart is still refused. A missing birth date is let through, as for gender.
     */
    public static final int MIN_OBSTETRIC_AGE_YEARS = 8;

    private static final Set<String> MALE = Set.of("m", "male", "homme", "masculin", "h");

    private ObstetricEligibility() {
    }

    /** True when the recorded gender unambiguously means male. */
    public static boolean isRecordedMale(Patient patient) {
        if (patient == null || patient.getGender() == null) {
            return false;
        }
        return MALE.contains(patient.getGender().trim().toLowerCase(Locale.ROOT));
    }

    /** Throws when an obstetric record must not be created for this patient. */
    public static void requireObstetricEligible(Patient patient) {
        if (isRecordedMale(patient)) {
            throw new BusinessException(MESSAGE_KEY);
        }
        if (isRecordedChild(patient)) {
            throw new BusinessException(TOO_YOUNG_MESSAGE_KEY, MIN_OBSTETRIC_AGE_YEARS);
        }
    }

    /** True when the recorded birth date makes the patient younger than {@link #MIN_OBSTETRIC_AGE_YEARS}. */
    public static boolean isRecordedChild(Patient patient) {
        if (patient == null || patient.getDateOfBirth() == null) {
            return false;
        }
        return java.time.Period.between(patient.getDateOfBirth(), java.time.LocalDate.now(java.time.ZoneId.systemDefault())).getYears()
            < MIN_OBSTETRIC_AGE_YEARS;
    }
}
