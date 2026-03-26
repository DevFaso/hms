package com.bitnesttechs.hms.patient.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Generic API response envelope matching backend's ApiResponseWrapper<T>.
 * { "success": true, "message": "...", "data": { ... } }
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "success") val success: Boolean = false,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: T? = null
)

/**
 * Pagination wrapper matching Spring Page<T>.
 */
@JsonClass(generateAdapter = true)
data class PageDto<T>(
    @Json(name = "content") val content: List<T> = emptyList(),
    @Json(name = "totalElements") val totalElements: Long = 0,
    @Json(name = "totalPages") val totalPages: Int = 0,
    @Json(name = "number") val number: Int = 0,
    @Json(name = "size") val size: Int = 20,
    @Json(name = "last") val last: Boolean = true
)
