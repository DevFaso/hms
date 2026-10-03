package com.bitnesttechs.hms.patient.core.models

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R

// Family access enums, which the screens printed as the raw constant ("view lab results").
// Labels mirror the portal's PORTAL.ENUM.* wording in en/fr/es. Each `when`
// is exhaustive with no `else`: a constant added without a label does not
// compile. An unknown wire value maps to UNKNOWN (a neutral label).


/** A family-access (proxy) permission, as `ProxyResponse.permissions` lists it. */
enum class ProxyPermission {
    VIEW_RECORDS,
    VIEW_APPOINTMENTS,
    VIEW_MEDICATIONS,
    VIEW_LAB_RESULTS,
    VIEW_BILLING,
    BOOK_APPOINTMENTS,
    MANAGE_MEDICATIONS,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            VIEW_RECORDS -> R.string.proxy_permission_view_records
            VIEW_APPOINTMENTS -> R.string.proxy_permission_view_appointments
            VIEW_MEDICATIONS -> R.string.proxy_permission_view_medications
            VIEW_LAB_RESULTS -> R.string.proxy_permission_view_lab_results
            VIEW_BILLING -> R.string.proxy_permission_view_billing
            BOOK_APPOINTMENTS -> R.string.proxy_permission_book_appointments
            MANAGE_MEDICATIONS -> R.string.proxy_permission_manage_medications
            UNKNOWN -> R.string.proxy_permission_unknown
        }

    companion object {
        fun fromWire(raw: String?): ProxyPermission =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** A family-access (proxy) grant status. */
enum class ProxyStatus {
    ACTIVE,
    PENDING,
    EXPIRED,
    REVOKED,
    UNKNOWN;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            ACTIVE -> R.string.proxy_status_active
            PENDING -> R.string.proxy_status_pending
            EXPIRED -> R.string.proxy_status_expired
            REVOKED -> R.string.proxy_status_revoked
            UNKNOWN -> R.string.proxy_status_unknown
        }

    companion object {
        fun fromWire(raw: String?): ProxyStatus =
            entries.firstOrNull { it != UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
