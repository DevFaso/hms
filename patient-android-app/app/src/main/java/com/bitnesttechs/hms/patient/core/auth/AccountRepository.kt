package com.bitnesttechs.hms.patient.core.auth

import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.ChangePasswordRequest
import com.bitnesttechs.hms.patient.core.models.PasswordResetConfirm
import com.bitnesttechs.hms.patient.core.models.PasswordResetRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.bitnesttechs.hms.patient.core.network.ServerMessage
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of an account request: done, or a refusal to render from resources. */
sealed class AccountResult {
    object Done : AccountResult()
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : AccountResult()
}

/**
 * The account flows the web portal has and the app did not (parity phase 3):
 * forgot password, account activation and change password. The endpoints
 * are the portal's; like the portal, nothing here discloses whether an
 * address or a token exists.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStorage: TokenStorage
) {

    /** POST /auth/password/request: 204 whatever the address, so any HTTP answer is "sent". */
    suspend fun requestPasswordReset(email: String): AccountResult = call {
        api.requestPasswordReset(PasswordResetRequest(email.trim()))
        AccountResult.Done
    }

    /**
     * POST /auth/password/confirm with the token from the e-mailed link. The
     * backend answers 204 even for a bad token, so the app can only say
     * "if the code was valid" — the portal's success page claims more than it knows.
     */
    suspend fun confirmPasswordReset(token: String, newPassword: String): AccountResult = call {
        val response = api.confirmPasswordReset(PasswordResetConfirm(token.trim(), newPassword))
        if (response.isSuccessful) {
            // The password saved for biometric sign-in is the old one now (the
            // backend answers 204 whether or not the token was valid, so this
            // cannot tell; forgetting it only costs one typed sign-in).
            tokenStorage.clearCredentials()
            AccountResult.Done
        }
        else AccountResult.Failed(R.string.reset_failed, ServerMessage.parse(response.errorBody()?.string()))
    }

    /** POST /auth/resend-verification: the same neutral answer whatever the address. */
    suspend fun resendActivation(email: String): AccountResult = call {
        api.resendVerification(email.trim())
        AccountResult.Done
    }

    /** GET /auth/verify-email: 200 activates the account, 400 is an invalid or expired link. */
    suspend fun verifyEmail(email: String, token: String): AccountResult = call {
        val response = api.verifyEmail(email.trim(), token.trim())
        if (response.isSuccessful) AccountResult.Done else AccountResult.Failed(R.string.activation_failed)
    }

    /** POST /auth/me/change-password (authenticated). */
    suspend fun changePassword(current: String, new: String): AccountResult = call {
        val response = api.changePassword(ChangePasswordRequest(current, new))
        when {
            response.isSuccessful -> {
                // Keep biometric sign-in working: it replays the saved password.
                if (tokenStorage.savedUsername != null && tokenStorage.savedPassword != null) {
                    tokenStorage.savedPassword = new
                }
                AccountResult.Done
            }
            response.code() == 401 -> AccountResult.Failed(R.string.change_password_wrong_current)
            else -> AccountResult.Failed(
                R.string.change_password_failed,
                ServerMessage.parse(response.errorBody()?.string())
            )
        }
    }

    private inline fun call(block: () -> AccountResult): AccountResult =
        try {
            block()
        } catch (e: Exception) {
            AccountResult.Failed(R.string.login_error_unreachable)
        }

    companion object {
        /** The portal's rule, which the backend enforces too. */
        const val MIN_PASSWORD_LENGTH = 8

        /**
         * The patient may paste either the bare token or the whole e-mailed
         * link (`.../reset-password?token=…`, `.../verify?email=…&token=…`).
         * Returns the named query parameter of a link, or the trimmed text
         * itself when it is not a link.
         */
        fun fromLink(pasted: String, parameter: String): String? {
            val text = pasted.trim()
            if (text.isEmpty()) return null
            if (!text.contains("://") && !text.contains("?")) return if (parameter == "token") text else null
            return queryParameter(text, parameter)
        }

        /**
         * The backend formats these links without encoding (`String.format`),
         * so a `+` in an address is a literal plus: only %XX sequences are
         * decoded, never `+` into a space (android.net.Uri would do that).
         */
        private fun queryParameter(text: String, parameter: String): String? {
            val query = text.substringAfter('?', "").substringBefore('#')
            return query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == parameter }
                ?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
        }
    }
}
