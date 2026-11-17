package com.bitnesttechs.hms.patient.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCookieJarTest {

    private val profile = "https://dev.e-keneya.com/api/me/patient/profile".toHttpUrl()
    private val logout = "https://dev.e-keneya.com/api/auth/logout".toHttpUrl()
    private fun cookie(name: String, value: String) =
        Cookie.Builder().name(name).value(value).domain("dev.e-keneya.com").path("/").build()

    @Test
    fun `the logout path neither reads nor stores cookies`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(profile, listOf(cookie("XSRF-TOKEN", "a")))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(logout))
        // The server's answer to a logout (clearing the refresh cookie) is ignored.
        jar.saveFromResponse(logout, listOf(cookie("XSRF-TOKEN", "")))
        assertEquals("a", jar.xsrfToken(profile))
        assertEquals(listOf("a"), jar.loadForRequest(profile).map { it.value })
    }

    @Test
    fun `clear forgets every cookie`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(profile, listOf(cookie("XSRF-TOKEN", "a")))
        jar.clear()
        assertNull(jar.xsrfToken(profile))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(profile))
    }
}
