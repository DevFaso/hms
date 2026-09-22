package com.bitnesttechs.hms.patient.features.medicalhistory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The patient's personal notes on each history section. As on the web
 * (encrypted localStorage) they never leave the device and are never sent to
 * the care team; here they live in their own EncryptedSharedPreferences file,
 * keyed by the signed-in user so two accounts on one phone do not share them.
 */
@Singleton
class HistoryNotesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: TokenStorage
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "medihub_history_notes",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun key(section: String): String = "${tokenStorage.userId ?: "session"}:$section"

    fun read(section: String): String = prefs.getString(key(section), null) ?: ""

    /** An empty note is removed rather than stored, as the web does. */
    fun write(section: String, value: String) {
        val editor = prefs.edit()
        if (value.isBlank()) editor.remove(key(section)) else editor.putString(key(section), value)
        editor.apply()
    }
}
