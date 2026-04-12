package com.bitnesttechs.hms.patient.features.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicationsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val medications = MutableStateFlow<List<MedicationDto>>(emptyList())
    val prescriptions = MutableStateFlow<List<PrescriptionDto>>(emptyList())
    val refills = MutableStateFlow<List<RefillDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                val m = async { api.getMedications().body()?.data ?: emptyList() }
                val p = async { api.getPrescriptions(size = 50).body()?.data ?: emptyList() }
                val r = async { api.getRefills().body()?.data?.content ?: emptyList() }
                medications.value = m.await()
                prescriptions.value = p.await()
                refills.value = r.await()
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }

    fun requestRefill(prescriptionId: String, pharmacy: String?, notes: String?) {
        viewModelScope.launch {
            try {
                val resp = api.requestRefill(RefillRequest(pharmacyId = pharmacy, notes = notes))
                if (resp.isSuccessful) {
                    _snackbar.value = "Refill requested successfully"
                    load()
                } else {
                    _snackbar.value = "Failed to request refill"
                }
            } catch (e: Exception) {
                _snackbar.value = "Error: ${e.message}"
            }
        }
    }

    fun clearSnackbar() { _snackbar.value = null }
}
