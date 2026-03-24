package com.bitnesttechs.hms.patient.labresults.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface LabResultsApi {

    @GET("me/patient/lab-results")
    suspend fun getLabResults(@Query("limit") limit: Int = 50): List<LabResultDto>
}

@Serializable
data class LabResultDto(
    val id: String = "",
    val testName: String? = null,
    val category: String? = null,
    val orderedBy: String? = null,
    val orderedDate: String? = null,
    val resultDate: String? = null,
    val status: String? = null,
    val results: List<LabTestResultDto>? = null,
    val notes: String? = null,
    val labName: String? = null
)

@Serializable
data class LabTestResultDto(
    val parameterName: String? = null,
    val value: String? = null,
    val unit: String? = null,
    val referenceRange: String? = null,
    val isAbnormal: Boolean? = null
)
