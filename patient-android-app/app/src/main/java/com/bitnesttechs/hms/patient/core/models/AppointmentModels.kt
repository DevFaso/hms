package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppointmentDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "appointmentDate") val appointmentDate: String = "",
    @Json(name = "appointmentTime") val appointmentTime: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "doctorName") val doctorName: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "reason") val reason: String? = null
) {
    val statusDisplay: String get() = status.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
}

@JsonClass(generateAdapter = true)
data class ScheduleAppointmentRequest(
    @Json(name = "appointmentDate") val appointmentDate: String,
    @Json(name = "appointmentTime") val appointmentTime: String,
    @Json(name = "type") val type: String,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "departmentId") val departmentId: Long? = null
)

@JsonClass(generateAdapter = true)
data class CancelAppointmentRequest(
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class RescheduleAppointmentRequest(
    @Json(name = "newDate") val newDate: String,
    @Json(name = "newTime") val newTime: String,
    @Json(name = "reason") val reason: String? = null
)
