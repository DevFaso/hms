package com.bitnesttechs.hms.patient.features.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.auth.AuthResult
import com.bitnesttechs.hms.patient.core.auth.KeycloakAuthService
import com.bitnesttechs.hms.patient.core.config.FeatureFlagManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val authRepository: AuthRepository,
    private val featureFlagManager: FeatureFlagManager,
    private val keycloakAuthService: KeycloakAuthService
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** SSO button visibility: flag ON *and* build has a non-empty issuer. */
    val ssoEnabled: StateFlow<Boolean> = featureFlagManager.keycloakSsoEnabled
        .map { it && keycloakAuthService.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /**
     * Called when the user taps "Sign in with SSO". Builds the AppAuth intent
     * on the IO dispatcher and hands it to [onIntent] so the Composable can
     * launch it via `rememberLauncherForActivityResult`.
     */
    fun startSsoLogin(onIntent: (Intent) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { keycloakAuthService.buildAuthorizationIntent() }
                .onSuccess { intent ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onIntent(intent)
                }
                .onFailure { ex ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = ex.message ?: "Unable to start SSO login"
                    )
                }
        }
    }

    /** Called from the Activity Result callback after the Custom Tab returns. */
    fun completeSsoLogin(data: Intent) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { keycloakAuthService.handleAuthorizationResponse(data) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                }
                .onFailure { ex ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = ex.message ?: "SSO login failed"
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        keycloakAuthService.dispose()
        super.onCleared()
    }
}
