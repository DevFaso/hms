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
    @Json(name = "staffName") val staffName: String? = null,
    @Json(name = "staffEmail") val staffEmail: String? = null,
    @Json(name = "departmentId") val departmentId: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null,
    @Json(name = "hospitalId") val hospitalId: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "reason") val reason: String? = null
) {
    val statusDisplay: String get() = status.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
    /** Display time range like "10:00 - 10:30" */
    val timeDisplay: String? get() {
        val s = startTime?.take(5)
        val e = endTime?.take(5)
        return if (s != null && e != null) "$s - $e" else s
    }
}

/**
 * POST /appointments to book a new appointment.
 * Requires at least one staff identifier: staffId, staffEmail, or staffUsername.
 */
@JsonClass(generateAdapter = true)
data class BookAppointmentRequest(
    @Json(name = "appointmentDate") val appointmentDate: String,
    @Json(name = "startTime") val startTime: String? = null,
    @Json(name = "endTime") val endTime: String? = null,
    @Json(name = "staffId") val staffId: String? = null,
    @Json(name = "staffEmail") val staffEmail: String? = null,
    @Json(name = "hospitalId") val hospitalId: String? = null,
    @Json(name = "departmentId") val departmentId: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "notes") val notes: String? = null
)

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
