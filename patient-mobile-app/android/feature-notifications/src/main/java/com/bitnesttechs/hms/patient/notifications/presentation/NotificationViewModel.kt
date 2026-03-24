package com.bitnesttechs.hms.patient.notifications.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.notifications.data.NotificationDto
import com.bitnesttechs.hms.patient.notifications.data.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationViewModel @Inject constructor(
    private val repository: NotificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.getNotifications()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        notifications = result.data,
                        unreadCount = result.data.count { it.read != true }
                    )
                }
                is Result.Error -> _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                else -> {}
            }
        }
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            when (repository.markAsRead(id)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map {
                            if (it.id == id) it.copy(read = true) else it
                        },
                        unreadCount = _uiState.value.unreadCount - 1
                    )
                }
                is Result.Error -> {} // Silent fail for mark-as-read
                else -> {}
            }
        }
    }
}

data class NotificationUiState(
    val isLoading: Boolean = false,
    val notifications: List<NotificationDto> = emptyList(),
    val unreadCount: Int = 0,
    val error: String? = null
)
