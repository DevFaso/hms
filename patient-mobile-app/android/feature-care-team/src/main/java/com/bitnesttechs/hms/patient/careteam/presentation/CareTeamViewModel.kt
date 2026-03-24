package com.bitnesttechs.hms.patient.careteam.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.network.Result
import com.bitnesttechs.hms.patient.careteam.data.CareTeamMemberDto
import com.bitnesttechs.hms.patient.careteam.data.CareTeamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CareTeamViewModel @Inject constructor(
    private val repository: CareTeamRepository
) : ViewModel() {

    private val _members = MutableStateFlow<List<CareTeamMemberDto>>(emptyList())
    val members = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { loadCareTeam() }

    fun loadCareTeam() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = repository.getCareTeam()) {
                is Result.Success -> _members.value = result.data
                is Result.Error -> _error.value = result.message
                else -> {}
            }
            _isLoading.value = false
        }
    }
}
