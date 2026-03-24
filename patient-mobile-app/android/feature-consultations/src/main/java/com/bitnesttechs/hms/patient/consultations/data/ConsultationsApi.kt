package com.bitnesttechs.hms.patient.consultations.data

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface ConsultationsApi {
    @GET("me/patient/consultations")
    suspend fun getConsultations(): ApiResponse<List<ConsultationDto>>
}

@Serializable
data class ConsultationDto(
    val id: String,
    val consultationType: String? = null,
    val reason: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val requestedDate: String? = null,
    val scheduledDate: String? = null,
    val completedDate: String? = null,
    val requestingDoctorName: String? = null,
    val requestingDepartment: String? = null,
    val consultantDoctorName: String? = null,
    val consultantDepartment: String? = null,
    val consultantSpecialty: String? = null,
    val hospitalName: String? = null,
    val findings: String? = null,
    val recommendations: String? = null,
    val diagnosis: String? = null,
    val notes: String? = null,
    val followUpRequired: Boolean? = null,
    val followUpDate: String? = null,
    val createdAt: String? = null
)
