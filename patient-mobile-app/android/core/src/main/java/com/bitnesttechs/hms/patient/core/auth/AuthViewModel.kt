package com.bitnesttechs.hms.patient.core.auth

import android.content.Context
import com.bitnesttechs.hms.patient.core.security.BiometricHelper
import com.bitnesttechs.hms.patient.core.security.TokenManager
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

sealed class AuthState {
    data object Loading : AuthState()
    data object Authenticated : AuthState()
    data object BiometricLocked : AuthState()
    data object Unauthenticated : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    val biometricHelper: BiometricHelper,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences("hms_settings", Context.MODE_PRIVATE)

    val isBiometricEnabled: Boolean
        get() = prefs.getBoolean(PREF_BIOMETRIC_ENABLED, false)

    private val _authState = MutableStateFlow<AuthState>(
        when {
            !tokenManager.isLoggedIn() -> AuthState.Unauthenticated
            isBiometricEnabled -> AuthState.BiometricLocked
            else -> AuthState.Authenticated
        }
    )
    val authState: StateFlow<AuthState> = _authState

    fun onLoginSuccess() {
        _authState.value = AuthState.Authenticated
    }

    fun onBiometricSuccess() {
        _authState.value = AuthState.Authenticated
    }

    fun skipBiometric() {
        _authState.value = AuthState.Authenticated
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun logout() {
        tokenManager.clearTokens()
        _authState.value = AuthState.Unauthenticated
    }

    companion object {
        private const val PREF_BIOMETRIC_ENABLED = "biometric_enabled"
    }
}
