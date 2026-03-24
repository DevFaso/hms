package com.bitnesttechs.hms.patient.vitals.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface VitalsApi {

    @GET("me/patient/vitals")
    suspend fun getVitals(@Query("limit") limit: Int = 50): List<VitalDto>

    @POST("me/patient/vitals")
    suspend fun recordVital(@Body request: RecordVitalRequest)
}

@Serializable
data class VitalDto(
    val id: String = "",
    val type: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val recordedAt: String? = null,
    val recordedBy: String? = null,
    val notes: String? = null,
    val source: String? = null
) {
    val displayValue: String
        get() {
            val v = value ?: return "—"
            val formatted = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
            return "$formatted ${unit.orEmpty()}"
        }
}

@Serializable
data class RecordVitalRequest(
    val type: String,
    val value: Double,
    val unit: String,
    val notes: String? = null
)
