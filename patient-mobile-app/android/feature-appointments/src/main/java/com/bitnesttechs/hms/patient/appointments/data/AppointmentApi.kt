package com.bitnesttechs.hms.patient.appointments.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface AppointmentApi {

    @GET("me/patient/appointments")
    suspend fun getAppointments(): List<AppointmentDto>

    @PUT("me/patient/appointments/cancel")
    suspend fun cancelAppointment(@Body request: CancelRequest)

    @PUT("me/patient/appointments/reschedule")
    suspend fun rescheduleAppointment(@Body request: RescheduleRequest)
}

@Serializable
data class AppointmentDto(
    val id: String = "",
    val appointmentDate: String? = null,
    val appointmentTime: String? = null,
    val status: String? = null,
    val appointmentType: String? = null,
    val reason: String? = null,
    val notes: String? = null,
    val doctorName: String? = null,
    val departmentName: String? = null,
    val hospitalName: String? = null,
    val location: String? = null
)

@Serializable
data class CancelRequest(val appointmentId: String)

@Serializable
data class RescheduleRequest(val appointmentId: String, val newDateTime: String)
