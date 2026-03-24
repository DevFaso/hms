package com.bitnesttechs.hms.patient.core.network

import kotlinx.serialization.Serializable

/** Wraps backend's ApiResponseWrapper<T> */
@Serializable
data class ApiResponse<T>(
    val status: String? = null,
    val message: String? = null,
    val data: T
)

/** Spring Page<T> wrapper */
@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val number: Int = 0,
    val size: Int = 20
)

/** Token response from /auth/login and /auth/token/refresh */
@Serializable
data class TokenResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val token: String? = null
) {
    val resolvedAccessToken: String
        get() = accessToken?.takeIf { it.isNotBlank() } ?: token.orEmpty()
}

/** Sealed result type for UI layer */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val code: Int? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}
