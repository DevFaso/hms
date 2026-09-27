package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R

// Visit history and after-visit summary enums, which the screens printed as the raw constant.
// Labels mirror the portal's PORTAL.ENUM.* wording in en/fr/es. Each `when`
// is exhaustive with no `else`: a constant added without a label does not
// compile. An unknown wire value maps to UNKNOWN (a neutral label).


/** `EncounterType` (hospital-core enums). */
enum class EncounterType {
    CONSULTATION,
    FOLLOW_UP,
    EMERGENCY,
    SURGERY,
    LAB,
    OUTPATIENT,
    INPATIENT,
    TELEHEALTH,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            CONSULTATION -> R.string.encounter_type_consultation
            FOLLOW_UP -> R.string.encounter_type_follow_up
            EMERGENCY -> R.string.encounter_type_emergency
            SURGERY -> R.string.encounter_type_surgery
            LAB -> R.string.encounter_type_lab
            OUTPATIENT -> R.string.encounter_type_outpatient
            INPATIENT -> R.string.encounter_type_inpatient
            TELEHEALTH -> R.string.encounter_type_telehealth
            UNKNOWN -> R.string.encounter_type_unknown
        }

    companion object {
        fun fromWire(raw: String?): EncounterType =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `EncounterStatus` (hospital-core enums). */
enum class EncounterStatus {
    SCHEDULED,
    ARRIVED,
    TRIAGE,
    WAITING_FOR_PHYSICIAN,
    IN_PROGRESS,
    AWAITING_RESULTS,
    READY_FOR_DISCHARGE,
    COMPLETED,
    CANCELLED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SCHEDULED -> R.string.encounter_status_scheduled
            ARRIVED -> R.string.encounter_status_arrived
            TRIAGE -> R.string.encounter_status_triage
            WAITING_FOR_PHYSICIAN -> R.string.encounter_status_waiting_for_physician
            IN_PROGRESS -> R.string.encounter_status_in_progress
            AWAITING_RESULTS -> R.string.encounter_status_awaiting_results
            READY_FOR_DISCHARGE -> R.string.encounter_status_ready_for_discharge
            COMPLETED -> R.string.encounter_status_completed
            CANCELLED -> R.string.encounter_status_cancelled
            UNKNOWN -> R.string.encounter_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): EncounterStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `DischargeDisposition` (hospital-core enums). */
enum class DischargeDisposition {
    HOME,
    HOME_WITH_HOME_HEALTH,
    SKILLED_NURSING_FACILITY,
    LONG_TERM_CARE_FACILITY,
    REHABILITATION_FACILITY,
    HOSPICE_HOME,
    HOSPICE_FACILITY,
    PSYCHIATRIC_FACILITY,
    AGAINST_MEDICAL_ADVICE,
    LEFT_WITHOUT_BEING_SEEN,
    TRANSFERRED_TO_ANOTHER_HOSPITAL,
    EXPIRED,
    OTHER,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            HOME -> R.string.discharge_disposition_home
            HOME_WITH_HOME_HEALTH -> R.string.discharge_disposition_home_with_home_health
            SKILLED_NURSING_FACILITY -> R.string.discharge_disposition_skilled_nursing_facility
            LONG_TERM_CARE_FACILITY -> R.string.discharge_disposition_long_term_care_facility
            REHABILITATION_FACILITY -> R.string.discharge_disposition_rehabilitation_facility
            HOSPICE_HOME -> R.string.discharge_disposition_hospice_home
            HOSPICE_FACILITY -> R.string.discharge_disposition_hospice_facility
            PSYCHIATRIC_FACILITY -> R.string.discharge_disposition_psychiatric_facility
            AGAINST_MEDICAL_ADVICE -> R.string.discharge_disposition_against_medical_advice
            LEFT_WITHOUT_BEING_SEEN -> R.string.discharge_disposition_left_without_being_seen
            TRANSFERRED_TO_ANOTHER_HOSPITAL -> R.string.discharge_disposition_transferred_to_another_hospital
            EXPIRED -> R.string.discharge_disposition_expired
            OTHER -> R.string.discharge_disposition_other
            UNKNOWN -> R.string.discharge_disposition_unknown
        }

    companion object {
        fun fromWire(raw: String?): DischargeDisposition =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
