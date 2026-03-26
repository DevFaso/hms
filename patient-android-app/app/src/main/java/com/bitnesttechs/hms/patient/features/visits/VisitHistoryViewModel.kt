package com.bitnesttechs.hms.patient.features.visits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.DischargeSummaryDto
import com.bitnesttechs.hms.patient.core.models.EncounterDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VisitHistoryViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _encounters = MutableStateFlow<List<EncounterDto>>(emptyList())
    val encounters: StateFlow<List<EncounterDto>> = _encounters

    private val _summaries = MutableStateFlow<List<DischargeSummaryDto>>(emptyList())
    val summaries: StateFlow<List<DischargeSummaryDto>> = _summaries

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val encDef = async { api.getEncounters() }
                val sumDef = async { api.getAfterVisitSummaries() }
                encDef.await().body()?.data?.let { _encounters.value = it }
                sumDef.await().body()?.data?.let { _summaries.value = it }
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun summaryForEncounter(encounterId: String): DischargeSummaryDto? =
        _summaries.value.firstOrNull { it.encounterId == encounterId }
}
