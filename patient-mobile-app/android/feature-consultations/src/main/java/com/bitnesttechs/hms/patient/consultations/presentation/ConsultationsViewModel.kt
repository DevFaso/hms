package com.bitnesttechs.hms.patient.consultations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.consultations.data.ConsultationDto
import com.bitnesttechs.hms.patient.consultations.data.ConsultationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsultationsViewModel @Inject constructor(
    private val repository: ConsultationsRepository
) : ViewModel() {

    private val _consultations = MutableStateFlow<List<ConsultationDto>>(emptyList())
    val consultations: StateFlow<List<ConsultationDto>> = _consultations

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { loadConsultations() }

    fun loadConsultations() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = repository.getConsultations()
                _consultations.value = response.data
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }
}
