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
                // ── Patient-only gate ──────────────────────────────────
                // The mobile app is exclusively for patients.  Reject
                // any user who does not hold ROLE_PATIENT.
                val roles = body.roles.orEmpty().map { it.uppercase() }
                if (!roles.any { it.contains("PATIENT") }) {
                    return AuthResult.Error(
                        "This app is for patients only. Please use the web portal to sign in."
                    )
                }

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
                val parsedMessage = parseErrorMessage(errorBody)
                val msg = when (response.code()) {
                    401 -> "Invalid username or password"
                    403 -> "Account locked or disabled"
                    404 -> "Server not reachable"
                    // KC-3 cutover (S-03): once the backend flips
                    // app.auth.oidc.required=true, /auth/login responds 410 Gone
                    // with a JSON body steering the user to SSO. Surface that
                    // message verbatim so the UI does not show a raw blob.
                    410 -> parsedMessage
                        ?: "Single sign-on is required. Please use the SSO button."
                    else -> parsedMessage ?: "Login failed (${response.code()})"
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

    /**
     * Extract a user-readable message from a Spring `MessageResponse`-style
     * error body (`{"message": "..."}` or `{"error": "..."}`). Returns null
     * when the body is empty, missing both fields, or unparseable so the
     * caller can fall back to status-code-specific copy.
     *
     * Implemented as a regex rather than a full JSON parser so it works in
     * pure-JVM unit tests without needing Robolectric to stub
     * {@code org.json.JSONObject}. This is intentionally lenient: malformed
     * input simply returns null, never throws.
     */
    internal fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        for (key in listOf("message", "error")) {
            val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            val raw = match?.groupValues?.getOrNull(1)
            if (!raw.isNullOrBlank()) {
                return unescapeJsonString(raw)
            }
        }
        return null
    }

    private fun unescapeJsonString(raw: String): String =
        raw.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
}
