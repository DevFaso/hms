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

/**
 * Login response is FLAT — user fields are at the top level, not nested under "user".
 */
@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "accessToken") val accessToken: String,
    @Json(name = "refreshToken") val refreshToken: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "firstName") val firstName: String? = null,
    @Json(name = "lastName") val lastName: String? = null,
    @Json(name = "roles") val roles: List<String>? = null,
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "profileType") val profileType: String? = null,
    @Json(name = "primaryHospitalId") val primaryHospitalId: String? = null,
    @Json(name = "primaryHospitalName") val primaryHospitalName: String? = null
) {
    /** Build a UserDto from the flat fields for backward compat. */
    val user: UserDto get() = UserDto(
        id = id ?: "",
        username = username ?: "",
        email = email ?: "",
        firstName = firstName ?: "",
        lastName = lastName ?: "",
        roles = roles ?: emptyList()
    )
}

// ── User / Patient ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: String = "",
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
    @Json(name = "id") val id: String = "",
    @Json(name = "firstName") val firstName: String = "",
    @Json(name = "lastName") val lastName: String = "",
    @Json(name = "middleName") val middleName: String? = null,
    @Json(name = "dateOfBirth") val dateOfBirth: String? = null,
    @Json(name = "gender") val gender: String? = null,
    @Json(name = "phoneNumberPrimary") val phoneNumberPrimary: String? = null,
    @Json(name = "phoneNumberSecondary") val phoneNumberSecondary: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "addressLine1") val addressLine1: String? = null,
    @Json(name = "addressLine2") val addressLine2: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "zipCode") val zipCode: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "bloodType") val bloodType: String? = null,
    @Json(name = "allergies") val allergies: String? = null,
    @Json(name = "emergencyContactName") val emergencyContactName: String? = null,
    @Json(name = "emergencyContactPhone") val emergencyContactPhone: String? = null,
    @Json(name = "emergencyContactRelationship") val emergencyContactRelationship: String? = null,
    @Json(name = "insuranceProvider") val insuranceProvider: String? = null,
    @Json(name = "insurancePolicyNumber") val insurancePolicyNumber: String? = null,
    @Json(name = "preferredPharmacy") val preferredPharmacy: String? = null,
    @Json(name = "mrn") val mrn: String? = null,
    @Json(name = "medicalRecordNumber") val medicalRecordNumber: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "profileImageUrl") val profileImageUrl: String? = null
) {
    val fullName: String get() = "$firstName $lastName".trim()
    val phone: String? get() = phoneNumberPrimary
    val address: String? get() {
        val parts = listOfNotNull(addressLine1, addressLine2, city, state, zipCode, country)
            .filter { it.isNotBlank() }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }
    val allergiesList: List<String>
        get() = allergies?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

/**
 * Fields the patient can update via PUT /me/patient/profile.
 */
@JsonClass(generateAdapter = true)
data class PatientProfileUpdateDto(
    @Json(name = "phoneNumberPrimary") val phoneNumberPrimary: String? = null,
    @Json(name = "phoneNumberSecondary") val phoneNumberSecondary: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "addressLine1") val addressLine1: String? = null,
    @Json(name = "addressLine2") val addressLine2: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "zipCode") val zipCode: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "emergencyContactName") val emergencyContactName: String? = null,
    @Json(name = "emergencyContactPhone") val emergencyContactPhone: String? = null,
    @Json(name = "emergencyContactRelationship") val emergencyContactRelationship: String? = null,
    @Json(name = "preferredPharmacy") val preferredPharmacy: String? = null
)

/**
 * Health summary response from GET /me/patient/health-summary.
 * Top-level keys: profile, recentLabResults, currentMedications, recentVitals,
 * immunizations, activeDiagnoses, allergies, chronicConditions
 */
@JsonClass(generateAdapter = true)
data class HealthSummaryDto(
    @Json(name = "profile") val profile: PatientProfileDto? = null,
    @Json(name = "recentLabResults") val recentLabResults: List<LabResultDto> = emptyList(),
    @Json(name = "currentMedications") val currentMedications: List<CurrentMedicationDto> = emptyList(),
    @Json(name = "recentVitals") val recentVitals: List<VitalSignDto> = emptyList(),
    @Json(name = "immunizations") val immunizations: List<ImmunizationDto> = emptyList(),
    @Json(name = "activeDiagnoses") val activeDiagnoses: List<String> = emptyList(),
    @Json(name = "allergies") val allergies: List<String> = emptyList(),
    @Json(name = "chronicConditions") val chronicConditions: List<String> = emptyList()
) {
    val medicationCount: Int get() = currentMedications.size
    val labResultCount: Int get() = recentLabResults.size
}

@JsonClass(generateAdapter = true)
data class CurrentMedicationDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "medicationName") val medicationName: String = "",
    @Json(name = "dosage") val dosage: String? = null,
    @Json(name = "frequency") val frequency: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null
)

// ── Profile Image Upload ──────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class ProfileImageResponse(
    @Json(name = "imageUrl") val imageUrl: String? = null,
    @Json(name = "message") val message: String? = null
)

// ── Proxy / Family Access ─────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class ProxyResponse(
    @Json(name = "id") val id: String = "",
    @Json(name = "grantorPatientId") val grantorPatientId: String? = null,
    @Json(name = "grantorName") val grantorName: String? = null,
    @Json(name = "granteePatientId") val granteePatientId: String? = null,
    @Json(name = "granteeName") val granteeName: String? = null,
    @Json(name = "relationship") val relationship: String? = null,
    @Json(name = "permissions") val permissions: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "grantedAt") val grantedAt: String? = null,
    @Json(name = "expiresAt") val expiresAt: String? = null,
    @Json(name = "revokedAt") val revokedAt: String? = null,
    @Json(name = "notes") val notes: String? = null
) {
    val permissionsList: List<String>
        get() = permissions?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

@JsonClass(generateAdapter = true)
data class GrantProxyRequest(
    @Json(name = "granteeUsername") val granteeUsername: String,
    @Json(name = "relationship") val relationship: String,
    @Json(name = "permissions") val permissions: String,
    @Json(name = "expiresAt") val expiresAt: String? = null,
    @Json(name = "notes") val notes: String? = null
)
