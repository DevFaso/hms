package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VitalSignDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "recordedAt") val recordedAt: String = "",
    @Json(name = "systolicBP") val systolicBP: Int? = null,
    @Json(name = "diastolicBP") val diastolicBP: Int? = null,
    @Json(name = "heartRate") val heartRate: Int? = null,
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "oxygenSaturation") val oxygenSaturation: Int? = null,
    @Json(name = "weight") val weight: Double? = null,
    @Json(name = "height") val height: Double? = null,
    @Json(name = "bmi") val bmi: Double? = null,
    @Json(name = "respiratoryRate") val respiratoryRate: Int? = null,
    @Json(name = "notes") val notes: String? = null
) {
    val bloodPressureDisplay: String?
        get() = if (systolicBP != null && diastolicBP != null) "$systolicBP/$diastolicBP mmHg" else null
    val heartRateDisplay: String?
        get() = heartRate?.let { "$it bpm" }
    val temperatureDisplay: String?
        get() = temperature?.let { "%.1f °C".format(it) }
    val oxygenDisplay: String?
        get() = oxygenSaturation?.let { "$it%" }
}

@JsonClass(generateAdapter = true)
data class RecordVitalRequest(
    @Json(name = "systolicBP") val systolicBP: Int? = null,
    @Json(name = "diastolicBP") val diastolicBP: Int? = null,
    @Json(name = "heartRate") val heartRate: Int? = null,
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "oxygenSaturation") val oxygenSaturation: Int? = null,
    @Json(name = "weight") val weight: Double? = null,
    @Json(name = "height") val height: Double? = null,
    @Json(name = "notes") val notes: String? = null
)
