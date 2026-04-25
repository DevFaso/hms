package com.bitnesttechs.hms.patient.core.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token storage backed by EncryptedSharedPreferences (AES256).
 */
@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "medihub_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var savedUsername: String?
        get() = prefs.getString(KEY_SAVED_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_SAVED_USERNAME, value).apply()

    var savedPassword: String?
        get() = prefs.getString(KEY_SAVED_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SAVED_PASSWORD, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    // ------------------------------------------------------------------
    // Keycloak / OIDC (KC-3) — additive. Stored alongside legacy fields
    // so the app can migrate without breaking existing sessions.
    // ------------------------------------------------------------------

    /** Serialized `net.openid.appauth.AuthState` JSON blob. */
    var oidcAuthStateJson: String?
        get() = prefs.getString(KEY_OIDC_AUTH_STATE, null)
        set(value) = prefs.edit().putString(KEY_OIDC_AUTH_STATE, value).apply()

    /** Cached OIDC access token (mirrors AuthState.accessToken for interceptors). */
    var oidcAccessToken: String?
        get() = prefs.getString(KEY_OIDC_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OIDC_ACCESS_TOKEN, value).apply()

    /** Cached OIDC ID token (for end-session / logout requests). */
    var oidcIdToken: String?
        get() = prefs.getString(KEY_OIDC_ID_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OIDC_ID_TOKEN, value).apply()

    val hasOidcSession: Boolean get() = oidcAuthStateJson != null

    val isLoggedIn: Boolean get() = accessToken != null || oidcAccessToken != null

    fun clearAll() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_OIDC_AUTH_STATE)
            .remove(KEY_OIDC_ACCESS_TOKEN)
            .remove(KEY_OIDC_ID_TOKEN)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_SAVED_USERNAME)
            .remove(KEY_SAVED_PASSWORD)
            .apply()
    }

    fun clearOidc() {
        prefs.edit()
            .remove(KEY_OIDC_AUTH_STATE)
            .remove(KEY_OIDC_ACCESS_TOKEN)
            .remove(KEY_OIDC_ID_TOKEN)
            .apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SAVED_USERNAME = "saved_username"
        private const val KEY_SAVED_PASSWORD = "saved_password"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_OIDC_AUTH_STATE = "oidc_auth_state"
        private const val KEY_OIDC_ACCESS_TOKEN = "oidc_access_token"
        private const val KEY_OIDC_ID_TOKEN = "oidc_id_token"
    }
}
