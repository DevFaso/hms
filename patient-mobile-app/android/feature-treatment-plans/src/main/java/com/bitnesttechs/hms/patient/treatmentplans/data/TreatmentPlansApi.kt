package com.bitnesttechs.hms.patient.treatmentplans.data

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.core.network.PageResponse
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface TreatmentPlansApi {
    @GET("me/patient/treatment-plans")
    suspend fun getTreatmentPlans(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): ApiResponse<PageResponse<TreatmentPlanDto>>
}

@Serializable
data class TreatmentPlanDto(
    val id: String,
    val planName: String? = null,
    val description: String? = null,
    val diagnosis: String? = null,
    val status: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val createdByName: String? = null,
    val createdBySpecialty: String? = null,
    val hospitalName: String? = null,
    val goals: List<TreatmentGoalDto>? = null,
    val activities: List<TreatmentActivityDto>? = null,
    val notes: String? = null,
    val createdAt: String? = null
)

@Serializable
data class TreatmentGoalDto(
    val id: String,
    val goalDescription: String? = null,
    val targetDate: String? = null,
    val status: String? = null,
    val progressPercentage: Int? = null
)

@Serializable
data class TreatmentActivityDto(
    val id: String,
    val activityType: String? = null,
    val description: String? = null,
    val frequency: String? = null,
    val duration: String? = null,
    val status: String? = null,
    val assignedTo: String? = null
)
