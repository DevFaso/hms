package com.bitnesttechs.hms.patient.features.appointments

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.di.ApplicationScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A snackbar message: a resource, plus the server's own detail when it gave one. */
data class AppointmentOutcome(@StringRes val resId: Int, val detail: String? = null)

/** Which of the wizard's three lists failed to load, so the sheet can retry just that one. */
enum class BookingStep { HOSPITALS, DEPARTMENTS, PROVIDERS }

/**
 * The booking wizard's data, hospital -> department -> provider, each list
 * loaded when the level above it is chosen (the web's flow). The selection
 * itself lives in the sheet; this holds what the server returned for it.
 */
data class BookingOptions(
    val hospitals: List<BookingHospitalDto> = emptyList(),
    val departments: List<BookingDepartmentDto> = emptyList(),
    val providers: List<BookingProviderDto> = emptyList(),
    val hospitalsLoaded: Boolean = false,
    val departmentsLoaded: Boolean = false,
    val providersLoaded: Boolean = false,
    val loading: BookingStep? = null,
    val loadError: BookingStep? = null,
    val isBooking: Boolean = false,
    val bookingError: AppointmentOutcome? = null
)

@HiltViewModel
class AppointmentsViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {
    private val _appointments = MutableStateFlow<List<AppointmentDto>>(emptyList())
    val appointments: StateFlow<List<AppointmentDto>> = _appointments.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()
    private val _actionResult = MutableStateFlow<AppointmentOutcome?>(null)
    val actionResult: StateFlow<AppointmentOutcome?> = _actionResult.asStateFlow()

    private val _bookingOptions = MutableStateFlow(BookingOptions())
    val bookingOptions: StateFlow<BookingOptions> = _bookingOptions.asStateFlow()
    /**
     * Owned here rather than in the composable: a rotation while a request is
     * out would otherwise drop the sheet, and the failure that came back
     * would never be shown.
     */
    private val _bookingSheetOpen = MutableStateFlow(false)
    val bookingSheetOpen: StateFlow<Boolean> = _bookingSheetOpen.asStateFlow()

    private var hospitalsJob: Job? = null
    private var departmentsJob: Job? = null
    private var providersJob: Job? = null
    private var bookingJob: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = false
            try {
                val resp = api.getAppointments(size = 50)
                if (resp.isSuccessful) {
                    _appointments.value = resp.body()?.data ?: emptyList()
                } else {
                    _loadError.value = true
                }
            } catch (e: Exception) {
                _loadError.value = true
            } finally { _isLoading.value = false }
        }
    }

    // ── Booking wizard ────────────────────────────────────────────────────────

    /**
     * Opens the sheet on a fresh wizard with the hospitals loading. The
     * in-flight flag is carried over inside the same atomic update the
     * request clears it through, so whichever of the two runs first the
     * flag ends up true only while a request is really out.
     */
    fun showBooking() {
        departmentsJob?.cancel()
        providersJob?.cancel()
        _bookingOptions.update { BookingOptions(isBooking = it.isBooking) }
        _bookingSheetOpen.value = true
        loadHospitals()
    }

    /** A changed provider, date or time makes the last refusal stale (a conflict is for one slot). */
    fun clearBookingError() {
        _bookingOptions.update { if (it.bookingError == null) it else it.copy(bookingError = null) }
    }

    /** A sheet waiting on its request stays; the answer closes it or shows inline. */
    fun hideBooking() {
        if (!_bookingOptions.value.isBooking) _bookingSheetOpen.value = false
    }

    fun loadHospitals() {
        hospitalsJob?.cancel()
        _bookingOptions.update { it.copy(loading = BookingStep.HOSPITALS, loadError = null) }
        hospitalsJob = viewModelScope.launch {
            val list = fetch { api.getBookingHospitals().body()?.data }
            _bookingOptions.update {
                if (list == null) it.copy(loading = null, loadError = BookingStep.HOSPITALS)
                else it.copy(hospitals = list, hospitalsLoaded = true, loading = null)
            }
        }
    }

    /**
     * A new hospital empties the two levels below it and cancels any load still
     * in flight for the old one, so a slow answer cannot land on the new choice.
     */
    fun selectHospital(hospitalId: String) {
        departmentsJob?.cancel()
        providersJob?.cancel()
        _bookingOptions.update {
            it.copy(
                departments = emptyList(), providers = emptyList(),
                departmentsLoaded = false, providersLoaded = false,
                loading = BookingStep.DEPARTMENTS, loadError = null, bookingError = null
            )
        }
        departmentsJob = viewModelScope.launch {
            val list = fetch { api.getBookingDepartments(hospitalId).body()?.data }
            _bookingOptions.update {
                if (list == null) it.copy(loading = null, loadError = BookingStep.DEPARTMENTS)
                else it.copy(departments = list, departmentsLoaded = true, loading = null)
            }
        }
    }

    fun selectDepartment(hospitalId: String, departmentId: String) {
        providersJob?.cancel()
        _bookingOptions.update {
            it.copy(
                providers = emptyList(), providersLoaded = false,
                loading = BookingStep.PROVIDERS, loadError = null, bookingError = null
            )
        }
        providersJob = viewModelScope.launch {
            val list = fetch { api.getBookingProviders(hospitalId, departmentId).body()?.data }
            _bookingOptions.update {
                if (list == null) it.copy(loading = null, loadError = BookingStep.PROVIDERS)
                else it.copy(providers = list, providersLoaded = true, loading = null)
            }
        }
    }

    /** Null on any failure; a cancelled job never reaches the update (CancellationException propagates). */
    private suspend fun <T> fetch(call: suspend () -> List<T>?): List<T>? =
        try { call() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * Runs in [applicationScope] for the same reason [cancelAppointment] does:
     * a tab change mid-flight must not abandon a request the server may have
     * already honoured. The sheet stays open until the answer closes it or
     * shows the error inline.
     */
    fun book(request: BookAppointmentRequest) {
        if (bookingJob?.isActive == true) return
        _bookingOptions.update { it.copy(isBooking = true, bookingError = null) }
        bookingJob = applicationScope.launch {
            try {
                val resp = api.bookAppointment(request)
                if (resp.isSuccessful) {
                    _bookingOptions.update { it.copy(isBooking = false) }
                    _bookingSheetOpen.value = false
                    _actionResult.value = AppointmentOutcome(R.string.appointment_booked)
                    load()
                } else {
                    val detail = serverMessage(resp.errorBody()?.string())
                    _bookingOptions.update {
                        it.copy(isBooking = false, bookingError = AppointmentOutcome(R.string.booking_failed, detail))
                    }
                }
            } catch (e: Exception) {
                _bookingOptions.update {
                    it.copy(isBooking = false, bookingError = AppointmentOutcome(R.string.booking_failed, e.message))
                }
            }
        }
    }

    // ── Cancel / reschedule ───────────────────────────────────────────────────

    /**
     * Runs in [applicationScope], not [viewModelScope]: the same tap that
     * confirms the cancellation also pops the back stack, and a later tab
     * change clears the owning entry's ViewModelStore. On a slow link that
     * cancelled the POST in flight, leaving the appointment booked with
     * nothing shown to the patient.
     */
    fun cancelAppointment(appointmentId: String, reason: String?) {
        applicationScope.launch {
            try {
                val resp = api.cancelAppointment(
                    CancelAppointmentRequest(appointmentId = appointmentId, reason = reason)
                )
                if (resp.isSuccessful) {
                    _actionResult.value = AppointmentOutcome(R.string.appointment_cancelled)
                    load()
                } else {
                    _actionResult.value = AppointmentOutcome(
                        R.string.cancel_failed, serverMessage(resp.errorBody()?.string())
                    )
                }
            } catch (e: Exception) {
                _actionResult.value = AppointmentOutcome(R.string.cancel_failed, e.message)
            }
        }
    }

    /** newEndTime is required by the backend (@NotNull, must follow the start). */
    fun rescheduleAppointment(appointmentId: String, newDate: String, newStartTime: String, newEndTime: String) {
        viewModelScope.launch {
            try {
                val resp = api.rescheduleAppointment(
                    RescheduleAppointmentRequest(
                        appointmentId = appointmentId,
                        newDate = newDate,
                        newStartTime = newStartTime,
                        newEndTime = newEndTime
                    )
                )
                if (resp.isSuccessful) {
                    _actionResult.value = AppointmentOutcome(R.string.appointment_rescheduled)
                    load()
                } else {
                    _actionResult.value = AppointmentOutcome(
                        R.string.reschedule_failed, serverMessage(resp.errorBody()?.string())
                    )
                }
            } catch (e: Exception) {
                _actionResult.value = AppointmentOutcome(R.string.reschedule_failed, e.message)
            }
        }
    }

    fun clearActionResult() { _actionResult.value = null }

    /** The wrapper's message, when the body is the usual ApiResponseWrapper. */
    private fun serverMessage(body: String?): String? = body
        ?.let { runCatching { org.json.JSONObject(it).optString("message") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}
