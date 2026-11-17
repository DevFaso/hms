package com.bitnesttechs.hms.patient.features.medicalhistory

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The patient's personal notes on each history section. As on the web
 * (encrypted localStorage) they never leave the device and are never sent to
 * the care team; here they live in their own EncryptedSharedPreferences file.
 *
 * Every note is filed under the signed-in patient's HMS user id ([bucket]),
 * which both sign-in paths now resolve from `/auth/session/bootstrap`. Until
 * then an SSO session filed its notes under the Keycloak `sub` — a different
 * UUID for the same patient — so the notes vanished and reappeared with the
 * button used; [bucket] moves those once (see [subjectMove]). When there is
 * no user id there is no bucket and the caller hides the notes rather than
 * share one bucket between whoever signs in next. A keyset that cannot be
 * opened (a backup restored on another device) makes reads empty and writes
 * fail; it never throws into the caller.
 */
@Singleton
class HistoryNotesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: TokenStorage
) {
    /** Null when the encrypted file cannot be opened on this device. */
    private val prefs by lazy {
        runCatching {
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
        }.onFailure { Log.w(TAG, "History notes store unavailable", it) }.getOrNull()
    }

    /** The signed-in patient's HMS user id, the same for both login paths, or null when there is none. */
    fun bucket(): String? {
        val userId = tokenStorage.userId?.takeIf { it.isNotBlank() } ?: return null
        jwtSubject(tokenStorage.oidcIdToken)?.let { sub -> moveSubjectNotes(sub, userId) }
        return userId
    }

    /**
     * The one-time move of the CURRENT SSO session's notes from its Keycloak
     * `sub` key to its user-id key. Only the sub of the token this session
     * holds is ever read, so notes filed by another patient's session on the
     * same phone stay where they are.
     */
    private fun moveSubjectNotes(sub: String, userId: String) {
        runCatching {
            val p = prefs ?: return
            val moves = subjectMove(p.all.keys, sub, userId)
            if (moves.isEmpty()) return
            val editor = p.edit()
            moves.forEach { (from, to) ->
                p.getString(from, null)?.let { editor.putString(to, it) }
                editor.remove(from)
            }
            editor.commit()
        }.onFailure { Log.w(TAG, "History notes could not be moved to the account key", it) }
    }

    fun read(bucket: String, section: String): String =
        runCatching { prefs?.getString(key(bucket, section), null) }
            .onFailure { Log.w(TAG, "History note unreadable", it) }
            .getOrNull() ?: ""

    /** An empty note is removed rather than stored, as the web does. False when the write did not land. */
    fun write(bucket: String, section: String, value: String): Boolean = runCatching {
        val p = prefs ?: return false
        val editor = p.edit()
        if (value.isBlank()) editor.remove(key(bucket, section)) else editor.putString(key(bucket, section), value)
        editor.commit()
    }.onFailure { Log.w(TAG, "History note not saved", it) }.getOrDefault(false)

    private fun key(bucket: String, section: String): String = "$bucket:$section"

    companion object {
        private const val TAG = "HistoryNotesStore"

        /**
         * old key -> new key for the notes filed under [sub] that belong under
         * [userId]. Empty when the two are the same identity, when there is
         * nothing under [sub], or when [userId] already has notes of its own:
         * two buckets are never merged, since a note typed on the password
         * path after the SSO one would be silently overwritten.
         */
        fun subjectMove(keys: Set<String>, sub: String, userId: String): Map<String, String> {
            if (sub == userId) return emptyMap()
            if (keys.any { it.startsWith("$userId:") }) return emptyMap()
            return keys.filter { it.startsWith("$sub:") }
                .associateWith { userId + it.substring(sub.length) }
        }

        /**
         * The `sub` claim of a JWT, read from its base64url payload with no
         * signature check: the token was issued to this app and is only used to
         * pick a local file key. Null for anything that is not a three-part JWT
         * with a string `sub`.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun jwtSubject(token: String?): String? {
            if (token.isNullOrBlank()) return null
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payload = runCatching {
                val raw = parts[1]
                val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
                String(Base64.UrlSafe.decode(padded), Charsets.UTF_8)
            }.getOrNull() ?: return null
            return SUB_CLAIM.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        }

        private val SUB_CLAIM = Regex("\"sub\"\\s*:\\s*\"([^\"\\\\]+)\"")
    }
}
