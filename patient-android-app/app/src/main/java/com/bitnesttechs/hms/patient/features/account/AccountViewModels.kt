package com.bitnesttechs.hms.patient.features.account

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.auth.AccountRepository
import com.bitnesttechs.hms.patient.core.auth.AccountRepository.Companion.MIN_PASSWORD_LENGTH
import com.bitnesttechs.hms.patient.core.auth.AccountResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A refusal shown under a form: the app's text, then the server's sentence when there is one. */
data class FormError(@StringRes val messageRes: Int, val detail: String? = null)

private fun AccountResult.Failed.toFormError() = FormError(messageRes, detail)

/** The portal's password rule, checked before a request is spent on it. */
internal fun passwordProblem(new: String, confirm: String): Int? = when {
    new.length < MIN_PASSWORD_LENGTH -> R.string.password_min_length
    new != confirm -> R.string.passwords_mismatch
    else -> null
}

// ── Forgot password ─────────────────────────────────────────────────────────

data class ForgotPasswordState(
    val email: String = "",
    val requesting: Boolean = false,
    /** The neutral "if that address is registered" message is showing. */
    val linkSent: Boolean = false,
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val confirming: Boolean = false,
    val done: Boolean = false,
    val error: FormError? = null
)

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val accounts: AccountRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordState())
    val state: StateFlow<ForgotPasswordState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onCode(value: String) = _state.update { it.copy(code = value, error = null) }
    fun onNewPassword(value: String) = _state.update { it.copy(newPassword = value, error = null) }
    fun onConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }

    /** The same neutral answer whether or not the address exists, as on the web. */
    fun requestLink() {
        val s = _state.value
        if (s.requesting) return
        if (s.email.isBlank()) {
            _state.update { it.copy(error = FormError(R.string.enter_email)) }
            return
        }
        _state.update { it.copy(requesting = true, error = null, linkSent = false) }
        viewModelScope.launch {
            val result = accounts.requestPasswordReset(s.email)
            _state.update {
                when (result) {
                    AccountResult.Done -> it.copy(requesting = false, linkSent = true)
                    is AccountResult.Failed -> it.copy(requesting = false, error = result.toFormError())
                }
            }
        }
    }

    fun confirm() {
        val s = _state.value
        if (s.confirming) return
        val token = AccountRepository.fromLink(s.code, "token")
        val problem = when {
            token == null -> R.string.reset_code_missing
            else -> passwordProblem(s.newPassword, s.confirmPassword)
        }
        if (problem != null || token == null) {
            _state.update { it.copy(error = FormError(problem ?: R.string.reset_code_missing)) }
            return
        }
        _state.update { it.copy(confirming = true, error = null) }
        viewModelScope.launch {
            val result = accounts.confirmPasswordReset(token, s.newPassword)
            _state.update {
                when (result) {
                    AccountResult.Done -> it.copy(confirming = false, done = true, newPassword = "", confirmPassword = "")
                    is AccountResult.Failed -> it.copy(confirming = false, error = result.toFormError())
                }
            }
        }
    }
}

// ── Activation ──────────────────────────────────────────────────────────────

data class ActivationState(
    val email: String = "",
    val link: String = "",
    val sending: Boolean = false,
    val linkSent: Boolean = false,
    val activating: Boolean = false,
    val activated: Boolean = false,
    val error: FormError? = null
)

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val accounts: AccountRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ActivationState())
    val state: StateFlow<ActivationState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onLink(value: String) = _state.update { it.copy(link = value, error = null) }

    fun resend() {
        val s = _state.value
        if (s.sending) return
        if (s.email.isBlank()) {
            _state.update { it.copy(error = FormError(R.string.enter_email)) }
            return
        }
        _state.update { it.copy(sending = true, error = null, linkSent = false) }
        viewModelScope.launch {
            val result = accounts.resendActivation(s.email)
            _state.update {
                when (result) {
                    AccountResult.Done -> it.copy(sending = false, linkSent = true)
                    is AccountResult.Failed -> it.copy(sending = false, error = result.toFormError())
                }
            }
        }
    }

    /**
     * The pasted activation link carries both the address and the token
     * (`/verify?email=…&token=…`); a bare token needs the address typed above.
     */
    fun activate() {
        val s = _state.value
        if (s.activating) return
        val token = AccountRepository.fromLink(s.link, "token")
        val email = AccountRepository.fromLink(s.link, "email") ?: s.email.trim().takeIf { it.isNotEmpty() }
        if (token == null || email == null) {
            _state.update { it.copy(error = FormError(R.string.activation_link_missing)) }
            return
        }
        _state.update { it.copy(activating = true, error = null) }
        viewModelScope.launch {
            val result = accounts.verifyEmail(email, token)
            _state.update {
                when (result) {
                    AccountResult.Done -> it.copy(activating = false, activated = true)
                    is AccountResult.Failed -> it.copy(activating = false, error = result.toFormError())
                }
            }
        }
    }
}

// ── Change password ─────────────────────────────────────────────────────────

data class ChangePasswordState(
    val current: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val saving: Boolean = false,
    val done: Boolean = false,
    val error: FormError? = null
)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val accounts: AccountRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordState())
    val state: StateFlow<ChangePasswordState> = _state.asStateFlow()

    fun onCurrent(value: String) = _state.update { it.copy(current = value, error = null) }
    fun onNewPassword(value: String) = _state.update { it.copy(newPassword = value, error = null) }
    fun onConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }

    fun submit() {
        val s = _state.value
        if (s.saving) return
        val problem = when {
            s.current.isEmpty() -> R.string.change_password_current_required
            s.newPassword == s.current -> R.string.change_password_same
            else -> passwordProblem(s.newPassword, s.confirmPassword)
        }
        if (problem != null) {
            _state.update { it.copy(error = FormError(problem)) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = accounts.changePassword(s.current, s.newPassword)
            _state.update {
                when (result) {
                    AccountResult.Done -> ChangePasswordState(done = true)
                    is AccountResult.Failed -> it.copy(saving = false, error = result.toFormError())
                }
            }
        }
    }
}
