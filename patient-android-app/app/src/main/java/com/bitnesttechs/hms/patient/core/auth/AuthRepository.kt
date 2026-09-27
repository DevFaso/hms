package com.bitnesttechs.hms.patient.core.auth

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    object Success : AuthResult()

    /**
     * A refusal the screen renders from resources ([messageRes]) so it is in
     * the app language; [detail] is the server's own sentence where there is
     * one worth showing (it is localised from Accept-Language).
     */
    data class Error(@StringRes val messageRes: Int, val detail: String? = null) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage,
    private val keycloak: KeycloakAuthService,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    val isLoggedIn: Boolean get() = tokenStorage.isLoggedIn

    suspend fun login(username: String, password: String, saveCredentials: Boolean): AuthResult {
        return try {
            val response = api.login(LoginRequest(username, password))
            val body = response.body()
            val accessToken = body?.accessToken
            if (response.isSuccessful && body != null && accessToken != null) {
                // ── Patient-only gate ──────────────────────────────────
                // The mobile app is exclusively for patients.  Reject
                // any user who does not hold ROLE_PATIENT.
                if (!isPatient(body.roles)) {
                    return AuthResult.Error(R.string.login_error_patients_only)
                }

                // Login response is FLAT — token + user fields at top level
                tokenStorage.accessToken = accessToken
                tokenStorage.refreshToken = body.refreshToken
                val result = completeSignIn(sso = false, fallbackUserId = body.id)
                if (result is AuthResult.Success && saveCredentials) {
                    tokenStorage.savedUsername = username
                    tokenStorage.savedPassword = password
                }
                result
            } else if (response.isSuccessful) {
                AuthResult.Error(R.string.login_error_failed)
            } else {
                val errorBody = response.errorBody()?.string()
                val parsedMessage = parseErrorMessage(errorBody)
                when (response.code()) {
                    401 -> AuthResult.Error(R.string.login_error_invalid_credentials)
                    403 -> AuthResult.Error(R.string.login_error_locked)
                    404 -> AuthResult.Error(R.string.login_error_unreachable)
                    // KC-3 cutover (S-03): once the backend flips
                    // app.auth.oidc.required=true, /auth/login responds 410 Gone
                    // with a JSON body steering the user to SSO. Surface that
                    // message with our own headline so the UI does not show a raw blob.
                    410 -> AuthResult.Error(R.string.login_error_sso_required, parsedMessage)
                    else -> AuthResult.Error(R.string.login_error_failed, parsedMessage)
                }
            }
        } catch (e: Exception) {
            AuthResult.Error(R.string.login_error_unreachable)
        }
    }

    /**
     * The second half of every sign-in (password, MFA, SSO): resolve the HMS
     * user id from `/auth/session/bootstrap` and persist it, so chat and the
     * device-only notes find the same identity whichever button was used.
     *
     * The password login body already carries `users.id` ([fallbackUserId]),
     * so a bootstrap that cannot be reached there still leaves a usable
     * session. An SSO session has no other source — the token `sub` is the
     * Keycloak id — so there a failed bootstrap ends the session rather than
     * leave the patient signed in with chat and notes silently unusable.
     */
    suspend fun completeSignIn(sso: Boolean, fallbackUserId: String? = null): AuthResult {
        val boot = runCatching { api.getSessionBootstrap() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
        if (boot != null && !isPatient(boot.roles)) {
            tokenStorage.clearAll()
            return AuthResult.Error(R.string.login_error_patients_only)
        }
        val userId = boot?.userId?.takeIf { it.isNotBlank() }
            ?: fallbackUserId?.takeIf { !sso && it.isNotBlank() }
        if (userId == null) {
            tokenStorage.clearAll()
            return AuthResult.Error(R.string.login_error_session)
        }
        tokenStorage.userId = userId
        _currentUser.value = UserDto(
            id = userId,
            username = boot?.username.orEmpty(),
            email = boot?.email.orEmpty(),
            firstName = boot?.firstName.orEmpty(),
            lastName = boot?.lastName.orEmpty(),
            roles = boot?.roles.orEmpty()
        )
        return AuthResult.Success
    }

    /**
     * A session signed in before the bootstrap existed (an SSO session never
     * stored a user id) resolves it once, quietly: a failure here leaves the
     * session as it was and is retried at the next start.
     */
    suspend fun ensureUserId() {
        if (!tokenStorage.isLoggedIn || !tokenStorage.userId.isNullOrBlank()) return
        val boot = runCatching { api.getSessionBootstrap() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body() ?: return
        boot.userId?.takeIf { it.isNotBlank() }?.let { tokenStorage.userId = it }
    }

    suspend fun biometricLogin(): AuthResult {
        val username = tokenStorage.savedUsername
        val password = tokenStorage.savedPassword
        return if (username != null && password != null) {
            login(username, password, saveCredentials = false)
        } else {
            AuthResult.Error(R.string.login_error_no_saved_credentials)
        }
    }

    /**
     * Ends the session on the device at once and on the server as far as it
     * can be reached.
     *
     * The tokens are captured BEFORE the local state is cleared and the
     * server calls carry them explicitly (see [AuthInterceptor]: a request
     * that brings its own Authorization header never enters the
     * refresh-on-401 path, so a 401 on the way out cannot loop or resurrect
     * the session). Local state is cleared whatever the outcome, and the
     * revocation runs on the application scope so an unreachable server
     * never holds the patient on the sign-out screen.
     */
    suspend fun logout() {
        val ending = captureSessionEnd()
        tokenStorage.clearAll()
        _currentUser.value = null
        if (ending != null) appScope.launch { endSessionOnServer(ending) }
    }

    /** What the server needs to revoke the session, read while it still exists. */
    internal data class SessionEnd(
        val bearer: String,
        /** The HMS refresh token; null for an SSO session, whose token HMS cannot revoke. */
        val hmsRefreshToken: String?,
        val keycloakRevocation: KeycloakAuthService.Revocation?
    )

    internal fun captureSessionEnd(): SessionEnd? {
        val oidcToken = tokenStorage.oidcAccessToken
        val accessToken = oidcToken ?: tokenStorage.accessToken ?: return null
        return SessionEnd(
            bearer = "Bearer $accessToken",
            hmsRefreshToken = if (oidcToken == null) tokenStorage.refreshToken else null,
            keycloakRevocation = if (oidcToken != null) keycloak.pendingRevocation() else null
        )
    }

    /**
     * POST /auth/logout with the captured bearer and refresh token (the server
     * blacklists both jtis), then, for an SSO session, the refresh token to
     * Keycloak's revocation endpoint. Every step is best effort and bounded.
     */
    internal suspend fun endSessionOnServer(ending: SessionEnd) {
        withTimeoutOrNull(SERVER_SIGN_OUT_TIMEOUT_MS) {
            runCatching { api.logout(ending.bearer, LogoutRequest(ending.hmsRefreshToken)) }
        }
        ending.keycloakRevocation?.let { revocation ->
            withTimeoutOrNull(SERVER_SIGN_OUT_TIMEOUT_MS) { runCatching { keycloak.revoke(revocation) } }
        }
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

    private companion object {
        const val SERVER_SIGN_OUT_TIMEOUT_MS = 15_000L
    }

    private fun isPatient(roles: List<String>?): Boolean =
        roles.orEmpty().any { it.uppercase().contains("PATIENT") }

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
