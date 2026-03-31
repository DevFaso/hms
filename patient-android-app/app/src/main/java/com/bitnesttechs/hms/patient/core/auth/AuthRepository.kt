package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    object Success : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage
) {
    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    val isLoggedIn: Boolean get() = tokenStorage.isLoggedIn

    suspend fun login(username: String, password: String, saveCredentials: Boolean): AuthResult {
        return try {
            val response = api.login(LoginRequest(username, password))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // Login response is FLAT — token + user fields at top level
                tokenStorage.accessToken = body.accessToken
                tokenStorage.refreshToken = body.refreshToken
                tokenStorage.userId = body.id
                if (saveCredentials) {
                    tokenStorage.savedUsername = username
                    tokenStorage.savedPassword = password
                }
                _currentUser.value = body.user // computed property builds UserDto from flat fields
                AuthResult.Success
            } else {
                val errorBody = response.errorBody()?.string()
                val msg = when (response.code()) {
                    401 -> "Invalid username or password"
                    403 -> "Account locked or disabled"
                    404 -> "Server not reachable"
                    else -> errorBody?.take(200) ?: "Login failed (${response.code()})"
                }
                AuthResult.Error(msg)
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun biometricLogin(): AuthResult {
        val username = tokenStorage.savedUsername
        val password = tokenStorage.savedPassword
        return if (username != null && password != null) {
            login(username, password, saveCredentials = false)
        } else {
            AuthResult.Error("No saved credentials for biometric login")
        }
    }

    suspend fun logout() {
        try { api.logout() } catch (_: Exception) {}
        tokenStorage.clearAll()
        _currentUser.value = null
    }

    suspend fun loadCurrentUser(): UserDto? {
        return try {
            val resp = api.getProfile()
            val profile = resp.body()?.data
            if (profile != null) {
                val user = UserDto(
                    id = profile.id,
                    username = profile.username ?: "",
                    email = profile.email ?: "",
                    firstName = profile.firstName,
                    lastName = profile.lastName
                )
                _currentUser.value = user
                user
            } else null
        } catch (_: Exception) { null }
    }
}
