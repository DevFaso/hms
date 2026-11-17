package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R

// Appointment and invoice statuses, which the screens printed as the lower-cased constant.
// Labels mirror the portal's PORTAL.ENUM.* wording in en/fr/es. Each `when`
// is exhaustive with no `else`: a constant added without a label does not
// compile. An unknown wire value maps to UNKNOWN (a neutral label).


/** `AppointmentStatus` (hospital-core enums). */
enum class AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    CHECKED_IN,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
    PENDING,
    RESCHEDULED,
    IN_PROGRESS,
    FAILED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SCHEDULED -> R.string.appointment_status_scheduled
            CONFIRMED -> R.string.appointment_status_confirmed
            CHECKED_IN -> R.string.appointment_status_checked_in
            CANCELLED -> R.string.appointment_status_cancelled
            COMPLETED -> R.string.appointment_status_completed
            NO_SHOW -> R.string.appointment_status_no_show
            PENDING -> R.string.appointment_status_pending
            RESCHEDULED -> R.string.appointment_status_rescheduled
            IN_PROGRESS -> R.string.appointment_status_in_progress
            FAILED -> R.string.appointment_status_failed
            UNKNOWN -> R.string.appointment_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): AppointmentStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** `InvoiceStatus` (hospital-core enums). */
enum class InvoiceStatus {
    DRAFT,
    SENT,
    PARTIALLY_PAID,
    PAID,
    CANCELLED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            DRAFT -> R.string.invoice_status_draft
            SENT -> R.string.invoice_status_sent
            PARTIALLY_PAID -> R.string.invoice_status_partially_paid
            PAID -> R.string.invoice_status_paid
            CANCELLED -> R.string.invoice_status_cancelled
            UNKNOWN -> R.string.invoice_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): InvoiceStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
