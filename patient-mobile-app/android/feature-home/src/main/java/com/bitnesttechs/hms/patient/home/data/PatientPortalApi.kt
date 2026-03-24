package com.bitnesttechs.hms.patient.home.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface PatientPortalApi {

    @GET("me/patient/health-summary")
    suspend fun getHealthSummary(): HealthSummaryDto

    @GET("me/patient/profile")
    suspend fun getProfile(): PatientProfileDto
}

@Serializable
data class HealthSummaryDto(
    val upcomingAppointments: Int = 0,
    val activeMedications: Int = 0,
    val pendingLabResults: Int = 0,
    val unreadMessages: Int = 0
)

@Serializable
data class PatientProfileDto(
    val id: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val mrn: String? = null,
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val bloodType: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val emergencyContactRelation: String? = null
) {
    val fullName: String get() = listOfNotNull(firstName, lastName).joinToString(" ")
}
