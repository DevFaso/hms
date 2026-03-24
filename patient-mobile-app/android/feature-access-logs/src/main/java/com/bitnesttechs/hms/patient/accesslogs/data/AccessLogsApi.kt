package com.bitnesttechs.hms.patient.accesslogs.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface AccessLogsApi {

    @GET("me/patient/access-log")
    suspend fun getAccessLog(@Query("page") page: Int = 0, @Query("size") size: Int = 20): List<AccessLogEntryDto>
}

@Serializable
data class AccessLogEntryDto(
    val id: String = "",
    val accessedBy: String? = null,
    val accessedByRole: String? = null,
    val accessType: String? = null,
    val resourceType: String? = null,
    val accessDate: String? = null,
    val ipAddress: String? = null,
    val hospitalName: String? = null,
    val description: String? = null
)
