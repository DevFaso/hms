package com.bitnesttechs.hms.patient.features.visitsummaries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VisitSummariesViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {
    val summaries = MutableStateFlow<List<DischargeSummaryDto>>(emptyList())
    val isLoading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            try {
                summaries.value = api.getAfterVisitSummaries(size = 50).body()?.data ?: emptyList()
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                isLoading.value = false
            }
        }
    }
}
