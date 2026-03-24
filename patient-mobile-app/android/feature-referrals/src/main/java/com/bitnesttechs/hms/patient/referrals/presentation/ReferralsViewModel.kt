package com.bitnesttechs.hms.patient.referrals.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.referrals.data.ReferralDto
import com.bitnesttechs.hms.patient.referrals.data.ReferralsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReferralsViewModel @Inject constructor(
    private val repository: ReferralsRepository
) : ViewModel() {

    private val _referrals = MutableStateFlow<List<ReferralDto>>(emptyList())
    val referrals: StateFlow<List<ReferralDto>> = _referrals

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { loadReferrals() }

    fun loadReferrals() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = repository.getReferrals()
                _referrals.value = response.data
            } catch (e: Exception) {
                _error.value = e.message
            }
            _isLoading.value = false
        }
    }
}
