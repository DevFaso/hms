package com.bitnesttechs.hms.patient.features.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.NotificationDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _notifications = MutableStateFlow<List<NotificationDto>>(emptyList())
    val notifications: StateFlow<List<NotificationDto>> = _notifications

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getNotifications()
                if (resp.isSuccessful) {
                    _notifications.value = resp.body()?.data?.content ?: emptyList()
                }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markRead(id: Long) {
        viewModelScope.launch {
            try {
                api.markNotificationRead(id)
                _notifications.value = _notifications.value.map {
                    if (it.id == id) it.copy(isRead = true) else it
                }
            } catch (_: Exception) {}
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                api.markAllNotificationsRead()
                _notifications.value = _notifications.value.map { it.copy(isRead = true) }
            } catch (_: Exception) {}
        }
    }
}
