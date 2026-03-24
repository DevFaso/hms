package com.bitnesttechs.hms.patient.vitals.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.vitals.data.RecordVitalRequest
import com.bitnesttechs.hms.patient.vitals.data.VitalDto
import com.bitnesttechs.hms.patient.vitals.data.VitalsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VitalsViewModel @Inject constructor(
    private val repository: VitalsRepository
) : ViewModel() {

    private val _vitals = MutableStateFlow<List<VitalDto>>(emptyList())
    val vitals = _vitals.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _recordSuccess = MutableStateFlow(false)
    val recordSuccess = _recordSuccess.asStateFlow()

    init { loadVitals() }

    fun loadVitals() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getVitals()) {
                is Result.Success -> _vitals.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun recordVital(type: String, value: Double, unit: String, notes: String?) {
        viewModelScope.launch {
            _error.value = null
            when (val result = repository.recordVital(RecordVitalRequest(type, value, unit, notes))) {
                is Result.Success -> {
                    _recordSuccess.value = true
                    loadVitals()
                }
                is Result.Error -> _error.value = result.message
                else -> {}
            }
        }
    }

    fun clearRecordSuccess() { _recordSuccess.value = false }
}
