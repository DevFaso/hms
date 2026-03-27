package com.bitnesttechs.hms.patient.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val healthSummary: HealthSummaryDto? = null,
    val upcomingAppointments: List<AppointmentDto> = emptyList(),
    val recentLabResults: List<LabResultDto> = emptyList(),
    val unreadNotificationCount: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init { loadDashboard() }

    fun loadDashboard() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val healthDeferred = async { api.getHealthSummary() }
                val appointmentsDeferred = async { api.getAppointments(size = 3) }
                val labsDeferred = async { api.getLabResults(size = 5) }
                val notificationsDeferred = async { api.getNotifications(size = 1) }

                val health = healthDeferred.await().body()?.data
                val appointments = appointmentsDeferred.await().body()?.data ?: emptyList()
                val labs = labsDeferred.await().body()?.data ?: emptyList()
                val notifications = notificationsDeferred.await().body()?.data ?: emptyList()
                val unreadCount = notifications.count { !it.isRead }

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    healthSummary = health,
                    upcomingAppointments = appointments,
                    recentLabResults = labs,
                    unreadNotificationCount = unreadCount
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
