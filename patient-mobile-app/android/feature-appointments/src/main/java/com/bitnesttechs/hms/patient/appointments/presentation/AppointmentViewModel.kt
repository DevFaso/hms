package com.bitnesttechs.hms.patient.appointments.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.appointments.data.AppointmentDto
import com.bitnesttechs.hms.patient.appointments.data.AppointmentRepository
import com.bitnesttechs.hms.patient.core.network.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class AppointmentViewModel @Inject constructor(
    private val repository: AppointmentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppointmentUiState())
    val uiState: StateFlow<AppointmentUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.getAppointments()) {
                is Result.Success -> {
                    val today = LocalDate.now()
                    val upcoming = result.data.filter { apt ->
                        apt.appointmentDate?.let {
                            try { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) >= today }
                            catch (_: Exception) { false }
                        } ?: false
                    }.sortedBy { it.appointmentDate }

                    val past = result.data.filter { apt ->
                        apt.appointmentDate?.let {
                            try { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) < today }
                            catch (_: Exception) { true }
                        } ?: true
                    }.sortedByDescending { it.appointmentDate }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        upcoming = upcoming,
                        past = past
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                else -> {}
            }
        }
    }

    fun cancelAppointment(id: String) {
        viewModelScope.launch {
            when (val result = repository.cancelAppointment(id)) {
                is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                else -> load()
            }
        }
    }

    fun rescheduleAppointment(id: String, newDateTime: String) {
        viewModelScope.launch {
            when (val result = repository.rescheduleAppointment(id, newDateTime)) {
                is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                else -> load()
            }
        }
    }
}

data class AppointmentUiState(
    val isLoading: Boolean = false,
    val upcoming: List<AppointmentDto> = emptyList(),
    val past: List<AppointmentDto> = emptyList(),
    val error: String? = null
)
