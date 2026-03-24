package com.bitnesttechs.hms.patient.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.chat.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<ConversationDto>>(emptyList())
    val conversations = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending = _isSending.asStateFlow()

    fun loadConversations(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getConversations(userId)) {
                is Result.Success -> _conversations.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun loadMessages(currentUserId: String, otherUserId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getChatHistory(currentUserId, otherUserId)) {
                is Result.Success -> _messages.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun sendMessage(senderId: String, recipientId: String, content: String) {
        viewModelScope.launch {
            _isSending.value = true
            when (repository.sendMessage(SendMessageRequest(senderId, recipientId, content))) {
                is Result.Success -> loadMessages(senderId, recipientId)
                is Result.Error -> {}
                else -> {}
            }
            _isSending.value = false
        }
    }

    fun markAsRead(senderId: String, recipientId: String) {
        viewModelScope.launch {
            repository.markAsRead(senderId, recipientId)
        }
    }
}
