package com.bitnesttechs.hms.patient.features.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
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
    val error: AuthResult.Error? = null,
    val isSuccess: Boolean = false,
    val hasSavedCredentials: Boolean = false,
    /** A password sign-in answered with an MFA challenge; the card asks for the code. */
    val mfa: AuthResult.MfaRequired? = null,
    /** An inline error on the MFA step (the toast is for the password step). */
    val mfaError: AuthResult.Error? = null,
    /** The last refusal may have been an inactive account: point at the activation flow. */
    val showActivationHint: Boolean = false
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
            _uiState.value = afterPasswordStep(result)
        }
    }

    private fun afterPasswordStep(result: AuthResult): LoginUiState = when (result) {
        is AuthResult.Success -> _uiState.value.copy(isLoading = false, isSuccess = true, showActivationHint = false)
        is AuthResult.MfaRequired -> _uiState.value.copy(isLoading = false, mfa = result, mfaError = null, showActivationHint = false)
        is AuthResult.Error -> _uiState.value.copy(isLoading = false, error = result, showActivationHint = result.offerActivation)
    }

    /** The second step: a TOTP or backup code for the pending challenge. */
    fun verifyMfa(code: String) {
        val challenge = _uiState.value.mfa ?: return
        if (_uiState.value.isLoading) return
        val trimmed = code.trim()
        if (trimmed.length !in 6..8) {
            _uiState.value = _uiState.value.copy(mfaError = AuthResult.Error(R.string.mfa_invalid_format))
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, mfaError = null)
            _uiState.value = when (val result = authRepository.verifyMfa(challenge.mfaToken, trimmed)) {
                is AuthResult.Success -> _uiState.value.copy(isLoading = false, isSuccess = true, mfa = null)
                is AuthResult.Error -> _uiState.value.copy(isLoading = false, mfaError = result)
                is AuthResult.MfaRequired -> _uiState.value.copy(isLoading = false, mfa = result)
            }
        }
    }

    /** Back from the code step to the password form. */
    fun cancelMfa() {
        _uiState.value = _uiState.value.copy(mfa = null, mfaError = null, isLoading = false)
    }

    fun biometricLogin() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authRepository.biometricLogin()
            _uiState.value = afterPasswordStep(result)
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
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = AuthResult.Error(R.string.sso_start_failed)
                    )
                }
        }
    }

    /** Called from the Activity Result callback after the Custom Tab returns. */
    fun completeSsoLogin(data: Intent) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val exchanged = runCatching { keycloakAuthService.handleAuthorizationResponse(data) }.isSuccess
            if (!exchanged) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = AuthResult.Error(R.string.sso_failed))
                return@launch
            }
            // The Keycloak token's `sub` is not the HMS user id; chat and the
            // device-only notes need that one, so resolve it before entering.
            _uiState.value = when (val result = authRepository.completeSignIn(sso = true)) {
                is AuthResult.Success -> _uiState.value.copy(isLoading = false, isSuccess = true)
                is AuthResult.Error -> _uiState.value.copy(isLoading = false, error = result)
                // Keycloak runs its own second factor; HMS never challenges an SSO session.
                is AuthResult.MfaRequired -> _uiState.value.copy(isLoading = false, error = AuthResult.Error(R.string.sso_failed))
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
