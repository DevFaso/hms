package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Auth Models ───────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class RefreshTokenRequest(
    @Json(name = "refreshToken") val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "accessToken") val accessToken: String,
    @Json(name = "refreshToken") val refreshToken: String? = null,
    @Json(name = "user") val user: UserDto? = null
)

// ── User / Patient ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "username") val username: String = "",
    @Json(name = "email") val email: String = "",
    @Json(name = "firstName") val firstName: String = "",
    @Json(name = "lastName") val lastName: String = "",
    @Json(name = "roles") val roles: List<String> = emptyList()
) {
    val fullName: String get() = "$firstName $lastName".trim()
}

@JsonClass(generateAdapter = true)
data class PatientProfileDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "firstName") val firstName: String = "",
    @Json(name = "lastName") val lastName: String = "",
    @Json(name = "dateOfBirth") val dateOfBirth: String? = null,
    @Json(name = "gender") val gender: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "address") val address: String? = null,
    @Json(name = "bloodType") val bloodType: String? = null,
    @Json(name = "allergies") val allergies: String? = null,
    @Json(name = "emergencyContactName") val emergencyContactName: String? = null,
    @Json(name = "emergencyContactPhone") val emergencyContactPhone: String? = null,
    @Json(name = "insuranceProvider") val insuranceProvider: String? = null,
    @Json(name = "insurancePolicyNumber") val insurancePolicyNumber: String? = null,
    @Json(name = "medicalRecordNumber") val medicalRecordNumber: String? = null
) {
    val fullName: String get() = "$firstName $lastName".trim()
    val allergiesList: List<String>
        get() = allergies?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

@JsonClass(generateAdapter = true)
data class HealthSummaryDto(
    @Json(name = "activeConditions") val activeConditions: List<String> = emptyList(),
    @Json(name = "currentMedications") val currentMedications: Int = 0,
    @Json(name = "upcomingAppointments") val upcomingAppointments: Int = 0,
    @Json(name = "pendingLabResults") val pendingLabResults: Int = 0,
    @Json(name = "outstandingBalance") val outstandingBalance: Double = 0.0,
    @Json(name = "allergies") val allergies: List<String> = emptyList(),
    @Json(name = "lastVisitDate") val lastVisitDate: String? = null,
    @Json(name = "bloodType") val bloodType: String? = null
)
