package com.bitnesttechs.hms.patient.records.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.records.data.EncounterDto
import com.bitnesttechs.hms.patient.records.data.RecordsRepository
import com.bitnesttechs.hms.patient.records.data.VisitSummaryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val repository: RecordsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordsUiState())
    val uiState: StateFlow<RecordsUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val encounterResult = repository.getEncounters()
            val summaryResult = repository.getAfterVisitSummaries()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                encounters = (encounterResult as? Result.Success)?.data ?: emptyList(),
                summaries = (summaryResult as? Result.Success)?.data ?: emptyList(),
                error = listOfNotNull(
                    (encounterResult as? Result.Error)?.message,
                    (summaryResult as? Result.Error)?.message
                ).firstOrNull()
            )
        }
    }
}

data class RecordsUiState(
    val isLoading: Boolean = false,
    val encounters: List<EncounterDto> = emptyList(),
    val summaries: List<VisitSummaryDto> = emptyList(),
    val error: String? = null
)
