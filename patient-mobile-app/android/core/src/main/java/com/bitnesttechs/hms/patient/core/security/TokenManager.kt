package com.bitnesttechs.hms.patient.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitnesttechs.hms.patient.core.network.Environment
import com.bitnesttechs.hms.patient.core.network.TokenResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "hms_secure_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun setAccessToken(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun isLoggedIn(): Boolean = getAccessToken() != null

    /**
     * Refresh the access token using the stored refresh token.
     * Returns true on success, false if refresh failed.
     */
    suspend fun refreshToken(): Boolean {
        val rt = getRefreshToken() ?: return false

        return try {
            val body = """{"refreshToken":"$rt"}"""
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${Environment.current.baseUrl}auth/token/refresh")
                .post(body)
                .build()

            // Use a plain client (no AuthInterceptor) to avoid recursion
            val response = OkHttpClient().newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return false
                val tokens = json.decodeFromString<TokenResponse>(responseBody)
                setAccessToken(tokens.resolvedAccessToken)
                tokens.refreshToken?.let { setRefreshToken(it) }
                true
            } else {
                clearTokens()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
