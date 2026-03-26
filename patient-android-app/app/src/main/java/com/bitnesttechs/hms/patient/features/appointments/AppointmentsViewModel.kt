package com.bitnesttechs.hms.patient.features.appointments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppointmentsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    private val _appointments = MutableStateFlow<List<AppointmentDto>>(emptyList())
    val appointments: StateFlow<List<AppointmentDto>> = _appointments.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getAppointments(size = 50)
                _appointments.value = resp.body()?.data?.content ?: emptyList()
            } catch (e: Exception) { _error.value = e.message }
            finally { _isLoading.value = false }
        }
    }
}
