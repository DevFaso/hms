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

    /** True while the last load failed, so the empty state can offer a retry. */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Retrofit does NOT throw on a non-2xx, so an expired session
                // arrives as isSuccessful == false with a null body — which
                // used to render as "No lab results" and tell the patient they
                // have none.
                val resp = api.getLabResults(limit = 50)
                resp.body()?.data?.let { _results.value = it }
                _loadFailed.value = !resp.isSuccessful
            } catch (_: Exception) {
                _loadFailed.value = true
            }
            finally { _isLoading.value = false }
        }
    }
}
