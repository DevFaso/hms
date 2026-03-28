package com.bitnesttechs.hms.patient.features.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage
) : ViewModel() {
    val conversations = MutableStateFlow<List<ChatConversationDto>>(emptyList())
    val careTeamMembers = MutableStateFlow<List<CareTeamMemberDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val isLoadingCareTeam = MutableStateFlow(false)

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                val userId = tokenStorage.userId ?: return@launch
                val resp = api.getChatConversations(userId)
                conversations.value = resp.body() ?: emptyList()
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }

    fun loadCareTeam() {
        viewModelScope.launch {
            isLoadingCareTeam.value = true
            try {
                val resp = api.getCareTeam()
                val team = resp.body()?.data
                val members = mutableListOf<CareTeamMemberDto>()
                team?.primaryPhysician?.let { members.add(it) }
                team?.members?.let { members.addAll(it) }
                val distinct = members.distinctBy { it.id }
                if (distinct.isNotEmpty()) {
                    careTeamMembers.value = distinct
                } else {
                    // Fallback: derive providers from appointment history
                    val apptResp = api.getAppointments(size = 50)
                    val appointments = apptResp.body()?.data ?: emptyList()
                    val fromAppointments = appointments
                        .filter { !it.staffUserId.isNullOrBlank() && !it.staffName.isNullOrBlank() }
                        .distinctBy { it.staffUserId }
                        .map {
                            CareTeamMemberDto(
                                id = it.staffUserId!!,
                                name = it.staffName!!,
                                role = "Provider",
                                department = it.hospitalName
                            )
                        }
                    careTeamMembers.value = fromAppointments
                }
            } catch (_: Exception) {}
            finally { isLoadingCareTeam.value = false }
        }
    }
}

@HiltViewModel
class MessageThreadViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage
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
                messages.value = (resp.body() ?: emptyList()).sortedBy { it.timestamp }
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
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
