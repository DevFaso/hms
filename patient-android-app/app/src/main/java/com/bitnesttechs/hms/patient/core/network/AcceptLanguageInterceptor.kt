package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.core.locale.LocaleHelper
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the app's own language (the one picked in Profile, not the phone's)
 * as `Accept-Language` on every API request, so the `message` the backend
 * composes — and that the screens append to their own localized headline —
 * is in the language the rest of the screen is in. The backend's
 * `parseLocale` reads this header.
 *
 * The language is read per request, not captured once: a change in Profile
 * recreates the activity but not this singleton.
 */
class AcceptLanguageInterceptor(
    private val language: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            request.newBuilder()
                .header(HEADER, LocaleHelper.normalize(language()))
                .build()
        )
    }

    companion object {
        const val HEADER = "Accept-Language"
    }
}
