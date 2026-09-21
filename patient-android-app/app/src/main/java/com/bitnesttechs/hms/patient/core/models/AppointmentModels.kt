package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppointmentDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "appointmentDate") val appointmentDate: String = "",
    @Json(name = "startTime") val startTime: String? = null,
    @Json(name = "endTime") val endTime: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "staffId") val staffId: String? = null,
    @Json(name = "staffUserId") val staffUserId: String? = null,
    @Json(name = "staffName") val staffName: String? = null,
    @Json(name = "staffEmail") val staffEmail: String? = null,
    @Json(name = "departmentId") val departmentId: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null,
    @Json(name = "hospitalId") val hospitalId: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "reason") val reason: String? = null
) : java.io.Serializable {
    val statusDisplay: String get() = status.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
    /** Display time range like "10:00 - 10:30" */
    /** Length of the booked slot; 30 minutes when the record carries no times. */
    val durationMinutes: Long get() {
        val s = startTime?.take(5)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        val e = endTime?.take(5)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        if (s == null || e == null) return 30
        val d = java.time.Duration.between(s, e).toMinutes()
        return if (d in 5..480) d else 30
    }

    val timeDisplay: String? get() {
        val s = startTime?.take(5)
        val e = endTime?.take(5)
        return if (s != null && e != null) "$s - $e" else s
    }
}

/**
 * POST /me/patient/appointments (PortalBookAppointmentRequestDTO). The backend
 * checks the registration, that the department belongs to the hospital, and
 * assigns the first available provider when staffId is null; endTime defaults
 * to startTime + 30 min. reason <= 500, notes <= 1000.
 */
@JsonClass(generateAdapter = true)
data class BookAppointmentRequest(
    @Json(name = "hospitalId") val hospitalId: String,
    @Json(name = "departmentId") val departmentId: String,
    @Json(name = "staffId") val staffId: String? = null,
    @Json(name = "date") val date: String,
    @Json(name = "startTime") val startTime: String,
    @Json(name = "endTime") val endTime: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "notes") val notes: String? = null
)

/** GET /me/patient/booking/hospitals — where the patient holds an active registration. */
@JsonClass(generateAdapter = true)
data class BookingHospitalDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "address") val address: String? = null
)

/** GET /me/patient/booking/hospitals/{id}/departments */
@JsonClass(generateAdapter = true)
data class BookingDepartmentDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null
)

/** GET /me/patient/booking/hospitals/{id}/departments/{id}/providers */
@JsonClass(generateAdapter = true)
data class BookingProviderDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "fullName") val fullName: String? = null,
    @Json(name = "role") val role: String? = null
) {
    /** The role is a raw ROLE_* constant with no translation, so the sheet shows the name only, as the web does. */
    val displayName: String get() = fullName?.takeIf { it.isNotBlank() } ?: name.orEmpty()
}

@JsonClass(generateAdapter = true)
data class CancelAppointmentRequest(
    @Json(name = "appointmentId") val appointmentId: String,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class RescheduleAppointmentRequest(
    @Json(name = "appointmentId") val appointmentId: String,
    @Json(name = "newStartTime") val newStartTime: String? = null,
    @Json(name = "newEndTime") val newEndTime: String? = null,
    @Json(name = "newDate") val newDate: String? = null,
    @Json(name = "reason") val reason: String? = null
)
