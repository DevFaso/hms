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

    private val _unreadCount = MutableStateFlow(0L)
    val unreadCount: StateFlow<Long> = _unreadCount

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getNotifications()
                if (resp.isSuccessful) {
                    val page = resp.body()?.data
                    _notifications.value = page?.content ?: emptyList()
                    _unreadCount.value = _notifications.value.count { !it.isRead }.toLong()
                }
                loadUnreadCount()
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
                    _unreadCount.value = _notifications.value.count { !it.isRead }.toLong()
                }
            } catch (_: Exception) {}
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                val resp = api.markAllNotificationsRead()
                if (resp.isSuccessful) {
                    _notifications.value = _notifications.value.map {
                        it.copy(isRead = true)
                    }
                    _unreadCount.value = 0
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun loadUnreadCount() {
        try {
            val resp = api.getUnreadNotificationCount()
            if (resp.isSuccessful) {
                _unreadCount.value = resp.body()?.data?.get("unreadCount") ?: _unreadCount.value
            }
        } catch (_: Exception) {}
    }
}
