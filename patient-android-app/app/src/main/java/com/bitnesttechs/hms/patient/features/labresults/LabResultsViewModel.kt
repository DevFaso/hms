package com.bitnesttechs.hms.patient.features.labresults

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.LabResultDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LabResultsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    private val _results = MutableStateFlow<List<LabResultDto>>(emptyList())
    val results: StateFlow<List<LabResultDto>> = _results.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try { _results.value = api.getLabResults(size = 50).body()?.data ?: emptyList() }
            catch (_: Exception) {}
            finally { _isLoading.value = false }
        }
    }
}
