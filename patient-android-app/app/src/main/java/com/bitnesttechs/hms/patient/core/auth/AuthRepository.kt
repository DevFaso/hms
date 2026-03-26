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
            if (response.isSuccessful && body?.success == true && body.data != null) {
                val data = body.data
                tokenStorage.accessToken = data.accessToken
                tokenStorage.refreshToken = data.refreshToken
                if (saveCredentials) {
                    tokenStorage.savedUsername = username
                    tokenStorage.savedPassword = password
                }
                _currentUser.value = data.user
                AuthResult.Success
            } else {
                AuthResult.Error(body?.message ?: "Login failed")
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
            resp.body()?.data?.also { _currentUser.value = it }
        } catch (_: Exception) { null }
    }
}
