package com.bitnesttechs.hms.patient.features.careteam

import com.bitnesttechs.hms.patient.core.network.AppText
import com.bitnesttechs.hms.patient.core.network.FailureText
import androidx.lifecycle.ViewModel
import com.bitnesttechs.hms.patient.R
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.models.CareTeamDto
import com.bitnesttechs.hms.patient.core.models.CareTeamMemberDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CareTeamViewModel @Inject constructor(
    private val api: ApiService
) : ViewModel() {

    private val _careTeam = MutableStateFlow<CareTeamDto?>(null)
    val careTeam: StateFlow<CareTeamDto?> = _careTeam

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = api.getCareTeam()
                if (response.isSuccessful) _careTeam.value = response.body()?.data
                else _error.value = AppText.get(R.string.care_team_load_failed, FailureText.http(response.code()))
            } catch (e: Exception) {
                _error.value = AppText.get(R.string.care_team_load_failed, FailureText.of(e))
            } finally {
                _isLoading.value = false
            }
        }
    }
}
