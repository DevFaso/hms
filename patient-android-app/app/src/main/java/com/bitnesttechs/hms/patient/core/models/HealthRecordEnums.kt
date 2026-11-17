package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R

// Health-records wire enums with their patient-facing labels. The treatment
// plan and referral tabs printed the raw constant (`REVISIONS_REQUIRED`,
// `ACKNOWLEDGED`) in every language. Labels mirror the portal's
// PORTAL.ENUM.* / REFERRALS.SPECIALTY_OPTION.* wording in en/fr/es. Each
// `when` is exhaustive with no `else`, so a constant added here without a
// label does not compile, and StatusLabelResourcesTest checks every label
// exists in each locale.

/** `TreatmentPlanStatus` (hospital-core enums). */
enum class TreatmentPlanStatus {
    DRAFT,
    IN_REVIEW,
    REVISIONS_REQUIRED,
    APPROVED,
    ARCHIVED,
    CANCELLED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            DRAFT -> R.string.tp_status_draft
            IN_REVIEW -> R.string.tp_status_in_review
            REVISIONS_REQUIRED -> R.string.tp_status_revisions_required
            APPROVED -> R.string.tp_status_approved
            ARCHIVED -> R.string.tp_status_archived
            CANCELLED -> R.string.tp_status_cancelled
            UNKNOWN -> R.string.tp_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): TreatmentPlanStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `ReferralStatus` (hospital-core enums). */
enum class ReferralStatus {
    DRAFT,
    SUBMITTED,
    ACKNOWLEDGED,
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            DRAFT -> R.string.referral_status_draft
            SUBMITTED -> R.string.referral_status_submitted
            ACKNOWLEDGED -> R.string.referral_status_acknowledged
            SCHEDULED -> R.string.referral_status_scheduled
            IN_PROGRESS -> R.string.referral_status_in_progress
            COMPLETED -> R.string.referral_status_completed
            CANCELLED -> R.string.referral_status_cancelled
            REJECTED -> R.string.referral_status_rejected
            EXPIRED -> R.string.referral_status_expired
            UNKNOWN -> R.string.referral_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): ReferralStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `ReferralType` (hospital-core enums). */
enum class ReferralType {
    CONSULTATION,
    SHARED_CARE,
    TRANSFER_OF_CARE,
    EMERGENCY_TRANSFER,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            CONSULTATION -> R.string.referral_type_consultation
            SHARED_CARE -> R.string.referral_type_shared_care
            TRANSFER_OF_CARE -> R.string.referral_type_transfer_of_care
            EMERGENCY_TRANSFER -> R.string.referral_type_emergency_transfer
            UNKNOWN -> R.string.referral_type_unknown
        }

    companion object {
        fun fromWire(raw: String?): ReferralType =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `ReferralUrgency` (hospital-core enums). */
enum class ReferralUrgency {
    ROUTINE,
    PRIORITY,
    URGENT,
    EMERGENCY,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            ROUTINE -> R.string.referral_urgency_routine
            PRIORITY -> R.string.referral_urgency_priority
            URGENT -> R.string.referral_urgency_urgent
            EMERGENCY -> R.string.referral_urgency_emergency
            UNKNOWN -> R.string.referral_urgency_unknown
        }

    companion object {
        fun fromWire(raw: String?): ReferralUrgency =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `ReferralSpecialty` (hospital-core enums). */
enum class ReferralSpecialty {
    GENERAL_PRACTICE,
    INTERNAL_MEDICINE,
    FAMILY_MEDICINE,
    PEDIATRICS,
    EMERGENCY_MEDICINE,
    GENERAL_SURGERY,
    CARDIOTHORACIC_SURGERY,
    NEUROSURGERY,
    ORTHOPEDIC_SURGERY,
    PLASTIC_SURGERY,
    VASCULAR_SURGERY,
    UROLOGY,
    CARDIOLOGY,
    NEUROLOGY,
    GASTROENTEROLOGY,
    PULMONOLOGY,
    NEPHROLOGY,
    ENDOCRINOLOGY,
    RHEUMATOLOGY,
    HEMATOLOGY,
    ONCOLOGY,
    INFECTIOUS_DISEASE,
    OBSTETRICS_GYNECOLOGY,
    MATERNAL_FETAL_MEDICINE,
    REPRODUCTIVE_ENDOCRINOLOGY,
    MIDWIFERY,
    OPHTHALMOLOGY,
    OTOLARYNGOLOGY,
    AUDIOLOGY,
    PSYCHIATRY,
    PSYCHOLOGY,
    BEHAVIORAL_HEALTH,
    PHYSICAL_MEDICINE_REHABILITATION,
    PHYSICAL_THERAPY,
    OCCUPATIONAL_THERAPY,
    SPEECH_THERAPY,
    DERMATOLOGY,
    ALLERGY_IMMUNOLOGY,
    RADIOLOGY,
    INTERVENTIONAL_RADIOLOGY,
    PATHOLOGY,
    ANESTHESIOLOGY,
    PAIN_MANAGEMENT,
    PALLIATIVE_CARE,
    NUTRITION_DIETETICS,
    GENETICS,
    SLEEP_MEDICINE,
    GERIATRICS,
    SPORTS_MEDICINE,
    WOUND_CARE,
    OTHER,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            GENERAL_PRACTICE -> R.string.referral_specialty_general_practice
            INTERNAL_MEDICINE -> R.string.referral_specialty_internal_medicine
            FAMILY_MEDICINE -> R.string.referral_specialty_family_medicine
            PEDIATRICS -> R.string.referral_specialty_pediatrics
            EMERGENCY_MEDICINE -> R.string.referral_specialty_emergency_medicine
            GENERAL_SURGERY -> R.string.referral_specialty_general_surgery
            CARDIOTHORACIC_SURGERY -> R.string.referral_specialty_cardiothoracic_surgery
            NEUROSURGERY -> R.string.referral_specialty_neurosurgery
            ORTHOPEDIC_SURGERY -> R.string.referral_specialty_orthopedic_surgery
            PLASTIC_SURGERY -> R.string.referral_specialty_plastic_surgery
            VASCULAR_SURGERY -> R.string.referral_specialty_vascular_surgery
            UROLOGY -> R.string.referral_specialty_urology
            CARDIOLOGY -> R.string.referral_specialty_cardiology
            NEUROLOGY -> R.string.referral_specialty_neurology
            GASTROENTEROLOGY -> R.string.referral_specialty_gastroenterology
            PULMONOLOGY -> R.string.referral_specialty_pulmonology
            NEPHROLOGY -> R.string.referral_specialty_nephrology
            ENDOCRINOLOGY -> R.string.referral_specialty_endocrinology
            RHEUMATOLOGY -> R.string.referral_specialty_rheumatology
            HEMATOLOGY -> R.string.referral_specialty_hematology
            ONCOLOGY -> R.string.referral_specialty_oncology
            INFECTIOUS_DISEASE -> R.string.referral_specialty_infectious_disease
            OBSTETRICS_GYNECOLOGY -> R.string.referral_specialty_obstetrics_gynecology
            MATERNAL_FETAL_MEDICINE -> R.string.referral_specialty_maternal_fetal_medicine
            REPRODUCTIVE_ENDOCRINOLOGY -> R.string.referral_specialty_reproductive_endocrinology
            MIDWIFERY -> R.string.referral_specialty_midwifery
            OPHTHALMOLOGY -> R.string.referral_specialty_ophthalmology
            OTOLARYNGOLOGY -> R.string.referral_specialty_otolaryngology
            AUDIOLOGY -> R.string.referral_specialty_audiology
            PSYCHIATRY -> R.string.referral_specialty_psychiatry
            PSYCHOLOGY -> R.string.referral_specialty_psychology
            BEHAVIORAL_HEALTH -> R.string.referral_specialty_behavioral_health
            PHYSICAL_MEDICINE_REHABILITATION -> R.string.referral_specialty_physical_medicine_rehabilitation
            PHYSICAL_THERAPY -> R.string.referral_specialty_physical_therapy
            OCCUPATIONAL_THERAPY -> R.string.referral_specialty_occupational_therapy
            SPEECH_THERAPY -> R.string.referral_specialty_speech_therapy
            DERMATOLOGY -> R.string.referral_specialty_dermatology
            ALLERGY_IMMUNOLOGY -> R.string.referral_specialty_allergy_immunology
            RADIOLOGY -> R.string.referral_specialty_radiology
            INTERVENTIONAL_RADIOLOGY -> R.string.referral_specialty_interventional_radiology
            PATHOLOGY -> R.string.referral_specialty_pathology
            ANESTHESIOLOGY -> R.string.referral_specialty_anesthesiology
            PAIN_MANAGEMENT -> R.string.referral_specialty_pain_management
            PALLIATIVE_CARE -> R.string.referral_specialty_palliative_care
            NUTRITION_DIETETICS -> R.string.referral_specialty_nutrition_dietetics
            GENETICS -> R.string.referral_specialty_genetics
            SLEEP_MEDICINE -> R.string.referral_specialty_sleep_medicine
            GERIATRICS -> R.string.referral_specialty_geriatrics
            SPORTS_MEDICINE -> R.string.referral_specialty_sports_medicine
            WOUND_CARE -> R.string.referral_specialty_wound_care
            OTHER -> R.string.referral_specialty_other
            UNKNOWN -> R.string.referral_specialty_unknown
        }

    companion object {
        fun fromWire(raw: String?): ReferralSpecialty =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** The patient's recorded gender. The column is free text (`Patient.gender`, 10 chars), so only the
 * values the portal labels are mapped; anything else is shown as entered. */
enum class PatientGender {
    FEMALE,
    MALE,
    NON_BINARY,
    OTHER,
    PREFER_NOT_TO_SAY;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            FEMALE -> R.string.gender_female
            MALE -> R.string.gender_male
            NON_BINARY -> R.string.gender_non_binary
            OTHER -> R.string.gender_other
            PREFER_NOT_TO_SAY -> R.string.gender_prefer_not_to_say
        }

    companion object {
        /** Null for a value the portal has no label for; the caller shows it as entered. */
        fun fromWire(raw: String?): PatientGender? =
            entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_')?.replace('-', '_'), ignoreCase = true) }
    }
}
