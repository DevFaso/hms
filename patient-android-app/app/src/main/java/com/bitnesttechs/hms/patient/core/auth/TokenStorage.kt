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

    val isLoggedIn: Boolean get() = accessToken != null

    fun clearAll() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_SAVED_USERNAME)
            .remove(KEY_SAVED_PASSWORD)
            .apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SAVED_USERNAME = "saved_username"
        private const val KEY_SAVED_PASSWORD = "saved_password"
    }
}
