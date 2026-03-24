package com.bitnesttechs.hms.patient.medications.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface MedicationApi {

    @GET("me/patient/medications")
    suspend fun getMedications(@Query("limit") limit: Int = 50): List<MedicationDto>

    @GET("me/patient/prescriptions")
    suspend fun getPrescriptions(): List<PrescriptionDto>

    @GET("me/patient/refills")
    suspend fun getRefills(@Query("page") page: Int = 0, @Query("size") size: Int = 20): List<RefillDto>

    @POST("me/patient/refills")
    suspend fun requestRefill(@Body request: RefillRequest)

    @PUT("me/patient/refills/{id}/cancel")
    suspend fun cancelRefill(@Path("id") id: String)
}

@Serializable
data class MedicationDto(
    val id: String = "",
    val name: String? = null,
    val genericName: String? = null,
    val dosage: String? = null,
    val frequency: String? = null,
    val route: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String? = null,
    val prescribedBy: String? = null,
    val instructions: String? = null
) {
    val isActive: Boolean get() = status?.uppercase() == "ACTIVE"
}

@Serializable
data class PrescriptionDto(
    val id: String = "",
    val medicationName: String? = null,
    val dosage: String? = null,
    val frequency: String? = null,
    val quantity: Int? = null,
    val refillsRemaining: Int? = null,
    val prescribedDate: String? = null,
    val expiryDate: String? = null,
    val status: String? = null,
    val prescribedBy: String? = null
)

@Serializable
data class RefillDto(
    val id: String = "",
    val prescriptionId: String? = null,
    val medicationName: String? = null,
    val status: String? = null,
    val requestedDate: String? = null,
    val completedDate: String? = null
)

@Serializable
data class RefillRequest(val prescriptionId: String)
