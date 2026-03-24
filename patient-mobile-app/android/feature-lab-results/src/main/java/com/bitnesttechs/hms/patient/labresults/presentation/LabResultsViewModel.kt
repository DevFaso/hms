package com.bitnesttechs.hms.patient.labresults.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.labresults.data.LabResultDto
import com.bitnesttechs.hms.patient.labresults.data.LabResultsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LabResultsViewModel @Inject constructor(
    private val repository: LabResultsRepository
) : ViewModel() {

    private val _results = MutableStateFlow<List<LabResultDto>>(emptyList())
    val results = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { loadResults() }

    fun loadResults() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getLabResults()) {
                is Result.Success -> _results.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }
}
