package com.bitnesttechs.hms.patient.consents.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.consents.data.ConsentDto
import com.bitnesttechs.hms.patient.consents.data.ConsentsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsentsViewModel @Inject constructor(
    private val repository: ConsentsRepository
) : ViewModel() {

    private val _consents = MutableStateFlow<List<ConsentDto>>(emptyList())
    val consents = _consents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { loadConsents() }

    fun loadConsents() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getConsents()) {
                is Result.Success -> _consents.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun revokeConsent(fromId: String, toId: String) {
        viewModelScope.launch {
            repository.revokeConsent(fromId, toId)
            loadConsents()
        }
    }
}
