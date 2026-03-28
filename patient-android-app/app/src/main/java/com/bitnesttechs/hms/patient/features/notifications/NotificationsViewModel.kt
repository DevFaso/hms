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
                    _notifications.value = resp.body()?.content ?: emptyList()
                }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            try {
                val resp = api.markNotificationRead(id)
                if (resp.isSuccessful) {
                    _notifications.value = _notifications.value.map {
                        if (it.id == id) it.copy(isRead = true) else it
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                val unread = _notifications.value.filter { !it.isRead }
                val marked = mutableSetOf<String>()
                unread.forEach { notif ->
                    val resp = api.markNotificationRead(notif.id)
                    if (resp.isSuccessful) marked.add(notif.id)
                }
                if (marked.isNotEmpty()) {
                    _notifications.value = _notifications.value.map {
                        if (it.id in marked) it.copy(isRead = true) else it
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
