package com.bitnesttechs.hms.patient.medications.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.medications.data.MedicationDto
import com.bitnesttechs.hms.patient.medications.data.MedicationRepository
import com.bitnesttechs.hms.patient.medications.data.PrescriptionDto
import com.bitnesttechs.hms.patient.medications.data.RefillDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicationViewModel @Inject constructor(
    private val repository: MedicationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MedicationUiState())
    val uiState: StateFlow<MedicationUiState> = _uiState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val medsResult = repository.getMedications()
            val rxResult = repository.getPrescriptions()
            val refillsResult = repository.getRefills()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                medications = (medsResult as? Result.Success)?.data ?: emptyList(),
                prescriptions = (rxResult as? Result.Success)?.data ?: emptyList(),
                refills = (refillsResult as? Result.Success)?.data ?: emptyList(),
                error = listOfNotNull(
                    (medsResult as? Result.Error)?.message,
                    (rxResult as? Result.Error)?.message,
                    (refillsResult as? Result.Error)?.message
                ).firstOrNull()
            )
        }
    }

    fun requestRefill(prescriptionId: String) {
        viewModelScope.launch {
            when (val result = repository.requestRefill(prescriptionId)) {
                is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                else -> load()
            }
        }
    }

    fun cancelRefill(id: String) {
        viewModelScope.launch {
            when (val result = repository.cancelRefill(id)) {
                is Result.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                else -> load()
            }
        }
    }
}

data class MedicationUiState(
    val isLoading: Boolean = false,
    val medications: List<MedicationDto> = emptyList(),
    val prescriptions: List<PrescriptionDto> = emptyList(),
    val refills: List<RefillDto> = emptyList(),
    val error: String? = null
)
