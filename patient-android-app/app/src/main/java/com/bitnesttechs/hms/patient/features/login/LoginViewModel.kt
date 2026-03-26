package com.bitnesttechs.hms.patient.features.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.auth.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false,
    val hasSavedCredentials: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Check if saved credentials exist for biometric login
        _uiState.value = _uiState.value.copy(
            hasSavedCredentials = authRepository.isLoggedIn.not().let {
                // tokenStorage is injected inside authRepository — check via it
                false // will be updated below
            }
        )
    }

    fun checkBiometricAvailability(hasSavedCreds: Boolean) {
        _uiState.value = _uiState.value.copy(hasSavedCredentials = hasSavedCreds)
    }

    fun login(username: String, password: String, saveCredentials: Boolean = true) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authRepository.login(username, password, saveCredentials)
            _uiState.value = when (result) {
                is AuthResult.Success -> _uiState.value.copy(isLoading = false, isSuccess = true)
                is AuthResult.Error -> _uiState.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun biometricLogin() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authRepository.biometricLogin()
            _uiState.value = when (result) {
                is AuthResult.Success -> _uiState.value.copy(isLoading = false, isSuccess = true)
                is AuthResult.Error -> _uiState.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
