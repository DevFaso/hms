package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses obstetric records (labour episode, postpartum observation, prenatal
 * schedule, birth plan, high-risk pregnancy plan) for a patient whose record
 * says male (QA D46: a labour episode and postpartum observations were saved
 * for a MALE patient), or a child (QA D413). QA D1538 (round 40): also the
 * prenatal ultrasound order, the OB/GYN referral (every care context is a
 * pregnancy episode) and the antenatal programme enrolment;
 * {@code ObstetricEntryPointGuardTest} lists every obstetric entry point.
 *
 * <p><b>Emergencies.</b> The caller says whether the request is an emergency
 * ({@link #requireObstetricEligible(Patient, String, boolean)}), and an
 * emergency is never refused: the care is not delayed, a record that
 * contradicts it is corrected afterwards. What counts as one:
 * <ul>
 *   <li>an ultrasound order placed URGENT or STAT ({@code ClinicalPriority.isEmergency});</li>
 *   <li>an OB/GYN referral placed URGENT ({@code ObgynReferralUrgency} has no EMERGENCY value).</li>
 * </ul>
 * An emergency placed for a patient the rule would refuse is not silent: the
 * rule returns true, the order is flagged ({@code obstetricRuleSkipped}) for
 * review and the skip is audited ({@code OBSTETRIC_RULE_SKIPPED_EMERGENCY},
 * with the recorded sex and age). Whether the exemption stays is a clinical
 * decision still pending. An ultrasound order of type OTHER ("not covered by
 * standard categories") need not be prenatal and is not judged.
 * The labour episode, the postpartum observation and the newborn assessment
 * carry no emergency marker in their requests, nor do the birth plan, the
 * high-risk plan, the maternal history, the prenatal schedule or the antenatal
 * enrolment: they pass {@code false} (the one-argument form) and keep the D46 /
 * D413 refusal. Whether labour, postpartum and newborn records must instead
 * never refuse is a decision for the clinical lead. Provisional, pending
 * clinical approval.
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

    /**
     * QA D1538 (round 40): the request field a refusal is keyed on when the caller names none: the
     * patient the record is for, as the form's patient picker sends it.
     */
    public static final String PATIENT_FIELD = "patientId";

    /** Throws when an obstetric record must not be created for this patient, keyed on {@link #PATIENT_FIELD}; no emergency. */
    public static void requireObstetricEligible(Patient patient) {
        requireObstetricEligible(patient, PATIENT_FIELD, false);
    }

    /**
     * Throws when an obstetric record must not be created for this patient. QA D1538: the refusal
     * names {@code field}, so the form marks what was picked. Unknown sex or birth date refuses
     * nothing, and an {@code emergency} refuses nothing (the class comment). Provisional, pending
     * clinical approval.
     *
     * @return true when the rule was skipped for an emergency although the record makes the patient
     *     ineligible: the caller flags the record for review and audits the skip (QA D1538 review,
     *     {@code ObstetricRuleSkipRecorder}). False when the patient is eligible or nothing is known.
     */
    public static boolean requireObstetricEligible(Patient patient, String field, boolean emergency) {
        if (emergency) {
            return isIneligible(patient);
        }
        if (isRecordedMale(patient)) {
            throw new FieldBusinessException(field, MESSAGE_KEY);
        }
        if (isRecordedChild(patient)) {
            throw new FieldBusinessException(field, TOO_YOUNG_MESSAGE_KEY, MIN_OBSTETRIC_AGE_YEARS);
        }
        return false;
    }

    /** The record says male or a child: the rule would refuse an obstetric record that is not an emergency. */
    public static boolean isIneligible(Patient patient) {
        return isRecordedMale(patient) || isRecordedChild(patient);
    }

    /** The recorded age in whole years today, or null when the birth date is unknown (for the skip's audit). */
    public static Integer recordedAgeYears(Patient patient) {
        if (patient == null || patient.getDateOfBirth() == null) {
            return null;
        }
        return java.time.Period.between(patient.getDateOfBirth(), java.time.LocalDate.now(java.time.ZoneId.systemDefault())).getYears();
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
