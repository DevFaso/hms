package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MedicationDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "name") val name: String = "",
    @Json(name = "dosage") val dosage: String? = null,
    @Json(name = "frequency") val frequency: String? = null,
    @Json(name = "route") val route: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null,
    @Json(name = "prescribedBy") val prescribedBy: String? = null,
    @Json(name = "instructions") val instructions: String? = null,
    @Json(name = "isActive") val isActive: Boolean = true
)

@JsonClass(generateAdapter = true)
data class PrescriptionDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "medicationName") val medicationName: String = "",
    @Json(name = "dosage") val dosage: String? = null,
    @Json(name = "frequency") val frequency: String? = null,
    @Json(name = "quantity") val quantity: Int? = null,
    @Json(name = "refillsRemaining") val refillsRemaining: Int = 0,
    @Json(name = "prescribedDate") val prescribedDate: String? = null,
    @Json(name = "expiryDate") val expiryDate: String? = null,
    @Json(name = "prescribedBy") val prescribedBy: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "instructions") val instructions: String? = null
)

@JsonClass(generateAdapter = true)
data class RefillDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "prescriptionId") val prescriptionId: Long = 0,
    @Json(name = "requestedDate") val requestedDate: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class RefillRequest(
    @Json(name = "pharmacyId") val pharmacyId: Long? = null,
    @Json(name = "notes") val notes: String? = null
)
