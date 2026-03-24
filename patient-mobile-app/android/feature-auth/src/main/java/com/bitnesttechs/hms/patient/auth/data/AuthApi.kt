package com.bitnesttechs.hms.patient.auth.data

import com.bitnesttechs.hms.patient.core.network.TokenResponse
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/logout")
    suspend fun logout()

    @POST("auth/token/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): TokenResponse

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): LoginResponse

    @POST("auth/password/request-reset")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequest)
}

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val token: String? = null,
    val user: UserDto? = null
) {
    val resolvedAccessToken: String
        get() = accessToken?.takeIf { it.isNotBlank() } ?: token.orEmpty()
}

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val roles: List<String> = emptyList()
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    val firstName: String,
    val lastName: String
)

@Serializable
data class PasswordResetRequest(val email: String)
