package com.bitnesttechs.hms.patient.immunizations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.immunizations.data.ImmunizationDto
import com.bitnesttechs.hms.patient.immunizations.data.ImmunizationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImmunizationsViewModel @Inject constructor(
    private val repository: ImmunizationsRepository
) : ViewModel() {

    private val _immunizations = MutableStateFlow<List<ImmunizationDto>>(emptyList())
    val immunizations = _immunizations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { loadImmunizations() }

    fun loadImmunizations() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getImmunizations()) {
                is Result.Success -> _immunizations.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }
}
