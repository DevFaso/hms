package com.bitnesttechs.hms.patient.features.appointments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DoctorOption(
    val key: String,
    val staffId: String,
    val staffName: String,
    val staffEmail: String?,
    val hospitalId: String?,
    val hospitalName: String?,
    val departmentId: String?
)

@HiltViewModel
class AppointmentsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    private val _appointments = MutableStateFlow<List<AppointmentDto>>(emptyList())
    val appointments: StateFlow<List<AppointmentDto>> = _appointments.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _bookingResult = MutableStateFlow<String?>(null)
    val bookingResult: StateFlow<String?> = _bookingResult.asStateFlow()
    private val _actionResult = MutableStateFlow<String?>(null)
    val actionResult: StateFlow<String?> = _actionResult.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val resp = api.getAppointments(size = 50)
                _appointments.value = resp.body()?.data ?: emptyList()
            } catch (e: Exception) { _error.value = e.message }
            finally { _isLoading.value = false }
        }
    }

    /** Extract unique doctors from past appointments for the booking picker. */
    val doctorOptions: List<DoctorOption> get() {
        val seen = mutableSetOf<String>()
        return _appointments.value.mapNotNull { appt ->
            val staffId = appt.staffId ?: return@mapNotNull null
            if (seen.add(staffId)) {
                DoctorOption(
                    key = staffId,
                    staffId = staffId,
                    staffName = appt.staffName ?: "Unknown",
                    staffEmail = appt.staffEmail,
                    hospitalId = appt.hospitalId,
                    hospitalName = appt.hospitalName,
                    departmentId = appt.departmentId
                )
            } else null
        }
    }

    fun bookAppointment(request: BookAppointmentRequest) {
        viewModelScope.launch {
            try {
                val resp = api.bookAppointment(request)
                if (resp.isSuccessful) {
                    _bookingResult.value = "Appointment booked successfully!"
                    load() // refresh list
                } else {
                    _bookingResult.value = "Booking failed: ${resp.code()}"
                }
            } catch (e: Exception) {
                _bookingResult.value = "Error: ${e.message}"
            }
        }
    }

    fun cancelAppointment(appointmentId: String, reason: String?) {
        viewModelScope.launch {
            try {
                val resp = api.cancelAppointment(
                    appointmentId,
                    CancelAppointmentRequest(appointmentId = appointmentId, reason = reason)
                )
                if (resp.isSuccessful) {
                    _actionResult.value = "Appointment cancelled"
                    load()
                } else {
                    _actionResult.value = "Cancel failed: ${resp.code()}"
                }
            } catch (e: Exception) { _actionResult.value = "Error: ${e.message}" }
        }
    }

    fun rescheduleAppointment(appointmentId: String, newDate: String, newStartTime: String) {
        viewModelScope.launch {
            try {
                val resp = api.rescheduleAppointment(
                    appointmentId,
                    RescheduleAppointmentRequest(
                        appointmentId = appointmentId,
                        newDate = newDate,
                        newStartTime = newStartTime
                    )
                )
                if (resp.isSuccessful) {
                    _actionResult.value = "Appointment rescheduled"
                    load()
                } else {
                    _actionResult.value = "Reschedule failed: ${resp.code()}"
                }
            } catch (e: Exception) { _actionResult.value = "Error: ${e.message}" }
        }
    }

    fun clearBookingResult() { _bookingResult.value = null }
    fun clearActionResult() { _actionResult.value = null }
}
