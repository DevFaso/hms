package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LabResultDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "testName") val testName: String = "",
    @Json(name = "result") val result: String? = null,
    @Json(name = "unit") val unit: String? = null,
    @Json(name = "referenceRange") val referenceRange: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "collectionDate") val collectionDate: String? = null,
    @Json(name = "resultDate") val resultDate: String? = null,
    @Json(name = "orderedBy") val orderedBy: String? = null,
    @Json(name = "labName") val labName: String? = null,
    @Json(name = "notes") val notes: String? = null
) {
    val isAbnormal: Boolean get() = status.uppercase() == "ABNORMAL"
    val isCritical: Boolean get() = status.uppercase() == "CRITICAL"
    val statusDisplay: String get() = status.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }
}
