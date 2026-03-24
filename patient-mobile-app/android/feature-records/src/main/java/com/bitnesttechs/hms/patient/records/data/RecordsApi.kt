package com.bitnesttechs.hms.patient.records.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface RecordsApi {

    @GET("me/patient/encounters")
    suspend fun getEncounters(): List<EncounterDto>

    @GET("me/patient/after-visit-summaries")
    suspend fun getAfterVisitSummaries(): List<VisitSummaryDto>
}

@Serializable
data class EncounterDto(
    val id: String = "",
    val encounterDate: String? = null,
    val encounterType: String? = null,
    val status: String? = null,
    val chiefComplaint: String? = null,
    val diagnosis: String? = null,
    val doctorName: String? = null,
    val departmentName: String? = null,
    val hospitalName: String? = null,
    val notes: String? = null
)

@Serializable
data class VisitSummaryDto(
    val id: String = "",
    val visitDate: String? = null,
    val summary: String? = null,
    val diagnosis: String? = null,
    val instructions: String? = null,
    val followUpDate: String? = null,
    val doctorName: String? = null
)
