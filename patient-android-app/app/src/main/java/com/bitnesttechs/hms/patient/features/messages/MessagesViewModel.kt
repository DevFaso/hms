package com.bitnesttechs.hms.patient.features.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val threads = MutableStateFlow<List<ChatThreadDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    init { load() }
    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try { threads.value = api.getChatThreads().body()?.data ?: emptyList() }
            catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }
}

@HiltViewModel
class MessageThreadViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val messages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val isSending = MutableStateFlow(false)
    private var currentThreadId: Long = 0L

    fun loadThread(threadId: Long) {
        currentThreadId = threadId
        viewModelScope.launch {
            isLoading.value = true
            try { messages.value = api.getMessages(threadId, size = 100).body()?.data?.content ?: emptyList() }
            catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            isSending.value = true
            try {
                val resp = api.sendMessage(currentThreadId, SendMessageRequest(content = content))
                resp.body()?.data?.let { messages.value = messages.value + it }
            } catch (_: Exception) {}
            finally { isSending.value = false }
        }
    }
}
