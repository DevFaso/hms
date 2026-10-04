package com.bitnesttechs.hms.patient.features.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.bitnesttechs.hms.patient.core.push.PushRegistrar
import android.media.MediaPlayer
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage,
    private val pushRegistrar: PushRegistrar
) : ViewModel() {
    /** POST_NOTIFICATIONS is asked for once, here, where a message notification makes sense. */
    fun shouldAskNotificationPermission(): Boolean = pushRegistrar.shouldAskNotificationPermission()

    fun markNotificationPermissionAsked() = pushRegistrar.markNotificationPermissionAsked()

    val conversations = MutableStateFlow<List<ChatConversationDto>>(emptyList())
    val careTeamMembers = MutableStateFlow<List<ChatRecipient>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val isLoadingCareTeam = MutableStateFlow(false)

    // Loaded by the screen each time it is shown (see MessagesScreen), so the
    // unread badges reflect a thread just read when the patient comes back.

    fun load() {
        viewModelScope.launch {
            // The spinner is for the first load; a refresh keeps the rows.
            isLoading.value = conversations.value.isEmpty()
            try {
                val userId = tokenStorage.userId ?: return@launch
                val resp = api.getChatConversations(userId)
                conversations.value = resp.body() ?: emptyList()
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }

    /**
     * The picker's list: care team and appointment clinicians, each request
     * failing on its own (see [ChatRecipients.merge]).
     */
    fun loadCareTeam() {
        viewModelScope.launch {
            isLoadingCareTeam.value = true
            try {
                val team = runCatching { api.getCareTeam() }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()?.data
                val appointments = runCatching { api.getAppointments(size = 50) }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()?.data
                careTeamMembers.value = ChatRecipients.merge(team, appointments, tokenStorage.userId)
            } finally {
                isLoadingCareTeam.value = false
            }
        }
    }
}

@HiltViewModel
class MessageThreadViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage,
    private val attachmentCache: ChatAttachmentCache
) : ViewModel() {
    val messages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val isSending = MutableStateFlow(false)
    private var recipientId: String = ""

    val currentUserId: String get() = tokenStorage.userId ?: ""

    fun loadThread(otherUserId: String) {
        recipientId = otherUserId
        val userId = tokenStorage.userId ?: return
        viewModelScope.launch {
            isLoading.value = true
            try {
                val resp = api.getChatHistory(userId, otherUserId, size = 100)
                messages.value = (resp.body() ?: emptyList()).sortedBy { it.timestamp.orEmpty() }
            } catch (_: Exception) {}
            finally { isLoading.value = false }
            markRead(otherUserId, userId)
        }
    }

    /**
     * Opening the thread reads everything the other party sent: without this
     * the inbox badge never cleared and `chat/unread-count` (which also feeds
     * the portal's topbar badge) stayed inflated for this patient. Best
     * effort — a failure only leaves the badge as it was.
     */
    private suspend fun markRead(otherUserId: String, userId: String) {
        runCatching { api.markChatRead(senderId = otherUserId, recipientId = userId) }
    }

    // ── Attachments ─────────────────────────────────────────────────────

    /** Attachment id -> its downloaded file in the app-private cache. */
    val attachmentFiles = MutableStateFlow<Map<String, File>>(emptyMap())
    /** Ids whose download failed; tapping one tries again. */
    val failedAttachments = MutableStateFlow<Set<String>>(emptySet())
    private val downloading = mutableSetOf<String>()

    /** Downloads the attachment once (cached across opens); [onReady] gets the file. */
    fun fetchAttachment(attachment: ChatAttachmentDto, onReady: (File) -> Unit = {}) {
        attachmentFiles.value[attachment.id]?.let { onReady(it); return }
        if (!downloading.add(attachment.id)) return
        failedAttachments.update { it - attachment.id }
        viewModelScope.launch {
            val file = attachmentCache.file(attachment)
            downloading.remove(attachment.id)
            if (file != null) {
                attachmentFiles.update { it + (attachment.id to file) }
                onReady(file)
            } else {
                failedAttachments.update { it + attachment.id }
            }
        }
    }

    /** The voice note playing now, if any. */
    val playingAudioId = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null

    /** Plays a voice note from its cached file, or stops the one playing. */
    fun toggleAudio(attachment: ChatAttachmentDto) {
        if (playingAudioId.value == attachment.id) {
            stopAudio()
            return
        }
        fetchAttachment(attachment) { file -> play(attachment.id, file) }
    }

    private fun play(id: String, file: File) {
        stopAudio()
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopAudio() }
            mp.prepare()
            mp.start()
            player = mp
            playingAudioId.value = id
        }.onFailure {
            mp.release()
            failedAttachments.update { it + id }
        }
    }

    fun stopAudio() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingAudioId.value = null
    }

    override fun onCleared() {
        stopAudio()
        super.onCleared()
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            isSending.value = true
            try {
                val resp = api.sendChatMessage(
                    SendChatMessageRequest(recipientId = recipientId, content = content)
                )
                resp.body()?.let { messages.value = messages.value + it }
            } catch (_: Exception) {}
            finally { isSending.value = false }
        }
    }
}
