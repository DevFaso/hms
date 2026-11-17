package com.bitnesttechs.hms.patient.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The API client's in-memory cookies (the backend's XSRF-TOKEN, and the
 * refresh-token cookie it also writes at login).
 *
 * Two rules keep one session's sign-out away from the next session:
 *  - [clear] runs at sign-out, so nothing of the old session is sent later;
 *  - `POST /auth/logout` neither reads nor stores cookies here. Sign-out
 *    runs in the background and can land after the patient has already
 *    signed in again; had it used the jar, it would have carried the NEW
 *    session's cookies and the server's "clear the refresh cookie" answer
 *    would have wiped the new session's. It sends only what was captured
 *    when the old session ended ([AuthRepository.captureSessionEnd]).
 */
@Singleton
class SessionCookieJar @Inject constructor() : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (isLogout(url)) return
        store.getOrPut(url.host) { mutableListOf() }.apply {
            cookies.forEach { c -> removeAll { it.name == c.name }; add(c) }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (isLogout(url)) emptyList() else store[url.host].orEmpty().toList()

    /** The XSRF-TOKEN value this jar holds for [url]'s host, if any. */
    @Synchronized
    fun xsrfToken(url: HttpUrl): String? =
        store[url.host]?.firstOrNull { it.name == XSRF_COOKIE }?.value

    @Synchronized
    fun clear() = store.clear()

    companion object {
        const val XSRF_COOKIE = "XSRF-TOKEN"
        const val XSRF_HEADER = "X-XSRF-TOKEN"

        fun isLogout(url: HttpUrl): Boolean = url.encodedPath.endsWith("/auth/logout")
    }
}
