package com.bitnesttechs.hms.patient.accesslogs.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.accesslogs.data.AccessLogEntryDto
import com.bitnesttechs.hms.patient.accesslogs.data.AccessLogsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccessLogsViewModel @Inject constructor(
    private val repository: AccessLogsRepository
) : ViewModel() {

    private val _logs = MutableStateFlow<List<AccessLogEntryDto>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var currentPage = 0
    private var hasMore = true

    init { loadLogs(reset = true) }

    fun loadLogs(reset: Boolean = false) {
        if (reset) {
            currentPage = 0
            hasMore = true
            _logs.value = emptyList()
        }
        if (!hasMore) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getAccessLog(currentPage)) {
                is Result.Success -> {
                    _logs.value = if (reset) result.data else _logs.value + result.data
                    hasMore = result.data.size >= 20
                    currentPage++
                }
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }
}
