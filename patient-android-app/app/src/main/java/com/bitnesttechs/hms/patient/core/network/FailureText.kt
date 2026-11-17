package com.bitnesttechs.hms.patient.core.network

import android.content.Context
import androidx.annotation.StringRes
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * App text for code that has no Compose context (ViewModels, repositories),
 * resolved in the language picked in Profile at the moment it is read. The
 * application's own resources keep the language the process started with,
 * so a text read through them after a language change was in the old
 * language until the next cold start.
 */
object AppText {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes resId: Int, vararg args: Any): String {
        val context = appContext ?: return ""
        return LocaleHelper.applyLocale(context).getString(resId, *args)
    }
}

/**
 * The detail a screen appends to its own localized headline when a request
 * fails. It used to be `e.message` (Android's English "Unable to resolve
 * host …", "timeout") or "HTTP 500": English, and machine text, inside a
 * French screen. Now it is always one of the app's own sentences.
 */
object FailureText {

    @StringRes
    fun resFor(error: Throwable): Int = when (error) {
        is SocketTimeoutException -> R.string.error_timeout
        is UnknownHostException, is ConnectException -> R.string.error_network
        is IOException -> R.string.error_network
        else -> R.string.error_generic
    }

    @StringRes
    fun resForStatus(code: Int): Int = when {
        code == 401 -> R.string.error_session_expired
        code == 403 -> R.string.error_forbidden
        code == 404 -> R.string.error_not_found
        code >= 500 -> R.string.error_server
        else -> R.string.error_request_refused
    }

    fun of(error: Throwable): String = AppText.get(resFor(error))

    fun http(code: Int): String = AppText.get(resForStatus(code))
}
