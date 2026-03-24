package com.bitnesttechs.hms.patient.referrals.data

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface ReferralsApi {
    @GET("me/patient/referrals")
    suspend fun getReferrals(): ApiResponse<List<ReferralDto>>
}

@Serializable
data class ReferralDto(
    val id: String,
    val referralNumber: String? = null,
    val referralType: String? = null,
    val referralReason: String? = null,
    val clinicalNotes: String? = null,
    val urgency: String? = null,
    val status: String? = null,
    val referringDoctorName: String? = null,
    val referringDepartment: String? = null,
    val referringHospital: String? = null,
    val referredToDoctorName: String? = null,
    val referredToDepartment: String? = null,
    val referredToHospital: String? = null,
    val referralDate: String? = null,
    val expiryDate: String? = null,
    val appointmentDate: String? = null,
    val diagnosisCode: String? = null,
    val diagnosisDescription: String? = null,
    val completedDate: String? = null,
    val completionNotes: String? = null,
    val createdAt: String? = null
)
