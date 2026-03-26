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
    val isLoading = MutableStateFlow(true)

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                val m = async { api.getMedications().body()?.data ?: emptyList() }
                val p = async { api.getPrescriptions(size = 50).body()?.data?.content ?: emptyList() }
                medications.value = m.await()
                prescriptions.value = p.await()
            } catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }
}
