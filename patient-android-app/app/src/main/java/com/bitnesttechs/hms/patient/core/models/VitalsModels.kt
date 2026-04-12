package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VitalSignDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "registrationId") val registrationId: String? = null,
    @Json(name = "hospitalId") val hospitalId: String? = null,
    @Json(name = "recordedByStaffId") val recordedByStaffId: String? = null,
    @Json(name = "recordedByAssignmentId") val recordedByAssignmentId: String? = null,
    @Json(name = "recordedByName") val recordedByName: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "systolicBpMmHg") val systolicBpMmHg: Int? = null,
    @Json(name = "diastolicBpMmHg") val diastolicBpMmHg: Int? = null,
    @Json(name = "heartRateBpm") val heartRateBpm: Int? = null,
    @Json(name = "temperatureCelsius") val temperatureCelsius: Double? = null,
    @Json(name = "spo2Percent") val spo2Percent: Int? = null,
    @Json(name = "respiratoryRateBpm") val respiratoryRateBpm: Int? = null,
    @Json(name = "bloodGlucoseMgDl") val bloodGlucoseMgDl: Int? = null,
    @Json(name = "weightKg") val weightKg: Double? = null,
    @Json(name = "bodyPosition") val bodyPosition: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "clinicallySignificant") val clinicallySignificant: Boolean? = null,
    @Json(name = "recordedAt") val recordedAt: String = "",
    @Json(name = "createdAt") val createdAt: String? = null,
    @Json(name = "updatedAt") val updatedAt: String? = null
) {
    val bloodPressureDisplay: String?
        get() = if (systolicBpMmHg != null && diastolicBpMmHg != null) "$systolicBpMmHg/$diastolicBpMmHg mmHg" else null
    val heartRateDisplay: String?
        get() = heartRateBpm?.let { "$it bpm" }
    val temperatureDisplay: String?
        get() = temperatureCelsius?.let { "%.1f °C".format(it) }
    val oxygenDisplay: String?
        get() = spo2Percent?.let { "$it%" }
    val respiratoryRateDisplay: String?
        get() = respiratoryRateBpm?.let { "$it breaths/min" }
    val bloodGlucoseDisplay: String?
        get() = bloodGlucoseMgDl?.let { "$it mg/dL" }
    val weightDisplay: String?
        get() = weightKg?.let { "%.1f kg".format(it) }
    val sourceDisplay: String?
        get() = source?.replace("_", " ")?.split(" ")?.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    val recordedDateDisplay: String
        get() {
            return try {
                val parts = recordedAt.take(10).split("-")
                if (parts.size == 3) {
                    val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                    val month = months[parts[1].toInt() - 1]
                    val day = parts[2].toInt()
                    val year = parts[0]
                    "$month $day, $year"
                } else recordedAt.take(10)
            } catch (_: Exception) { recordedAt.take(10) }
        }
}
