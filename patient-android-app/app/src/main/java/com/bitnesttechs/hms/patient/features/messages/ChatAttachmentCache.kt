package com.bitnesttechs.hms.patient.features.messages

import android.content.Context
import com.bitnesttechs.hms.patient.core.models.ChatAttachmentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat attachment bytes, fetched through the authenticated client (they have
 * no public URL: the backend streams them to the message's sender or
 * recipient only) and kept in the app-private cache so a photo is not
 * downloaded again every time the thread is opened. The folder is PHI and is
 * deleted with the session ([com.bitnesttechs.hms.patient.core.auth.TokenStorage.clearAll]).
 */
@Singleton
class ChatAttachmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    /** The cached file, downloading it first when needed; null when it cannot be had. */
    suspend fun file(attachment: ChatAttachmentDto): File? = withContext(Dispatchers.IO) {
        if (attachment.id.isBlank()) return@withContext null
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, fileName(attachment))
        if (target.isFile && target.length() > 0) return@withContext target
        runCatching {
            val resp = api.downloadChatAttachment(attachment.id)
            val body = resp.body()
            if (!resp.isSuccessful || body == null) {
                resp.errorBody()?.close() // a @Streaming error body still holds the connection
                return@runCatching null
            }
            // Written aside and renamed, so a download cut halfway is never
            // mistaken for a cached file on the next open.
            val partial = File(dir, target.name + ".part")
            body.use { b -> partial.outputStream().use { out -> b.byteStream().copyTo(out) } }
            if (partial.length() == 0L || !partial.renameTo(target)) {
                partial.delete()
                null
            } else {
                target
            }
        }.getOrNull()
    }

    companion object {
        /** Under the app's cache dir; also cleared by TokenStorage.clearAll and served by FileProvider. */
        const val DIR = "chat_attachments"

        /** The attachment id (the only name the server guarantees unique) plus an extension a viewer understands. */
        fun fileName(attachment: ChatAttachmentDto): String {
            val safeId = attachment.id.filter { it.isLetterOrDigit() || it == '-' }
            return "$safeId.${extension(attachment.contentType)}"
        }

        private fun extension(contentType: String?): String =
            when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic" -> "heic"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/ogg" -> "ogg"
                "audio/webm" -> "webm"
                "audio/wav", "audio/x-wav" -> "wav"
                "application/pdf" -> "pdf"
                else -> "bin"
            }
    }
}
