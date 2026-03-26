package com.bitnesttechs.hms.patient.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.models.PatientProfileDto
import com.bitnesttechs.hms.patient.core.models.PatientProfileUpdateDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _profile = MutableStateFlow<PatientProfileDto?>(null)
    val profile: StateFlow<PatientProfileDto?> = _profile

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getProfile()
                if (resp.isSuccessful) _profile.value = resp.body()?.data
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProfile(update: PatientProfileUpdateDto) {
        viewModelScope.launch {
            try {
                val resp = api.updateProfile(update)
                if (resp.isSuccessful) {
                    _profile.value = resp.body()?.data
                    _saveResult.value = "Profile updated successfully"
                } else {
                    _saveResult.value = "Update failed: ${resp.code()}"
                }
            } catch (e: Exception) {
                _saveResult.value = "Error: ${e.message}"
            }
        }
    }

    fun clearSaveResult() { _saveResult.value = null }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _loggedOut.value = true
        }
    }
}
