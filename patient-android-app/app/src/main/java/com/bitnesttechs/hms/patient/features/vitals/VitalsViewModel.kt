package com.bitnesttechs.hms.patient.features.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.*
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VitalsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    val vitals = MutableStateFlow<List<VitalSignDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val isRecording = MutableStateFlow(false)
    init { load() }
    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            try { vitals.value = api.getVitals(size = 20).body()?.data ?: emptyList() }
            catch (_: Exception) {}
            finally { isLoading.value = false }
        }
    }
    fun recordVital(request: RecordVitalRequest) {
        viewModelScope.launch {
            isRecording.value = true
            try {
                api.recordVital(request).body()?.data?.let { vitals.value = listOf(it) + vitals.value }
            } catch (_: Exception) {}
            finally { isRecording.value = false }
        }
    }
}
