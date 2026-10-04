package com.bitnesttechs.hms.patient.core.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class AcceptLanguageInterceptorTest {

    private fun sentLanguage(appLanguage: String?, original: Request? = null): String? {
        val chain = original?.let { FakeChain(it) } ?: FakeChain()
        AcceptLanguageInterceptor { appLanguage.orEmpty() }.intercept(chain).close()
        return chain.proceeded.single().header("Accept-Language")
    }

    @Test
    fun `sends the app language on every request`() {
        assertEquals("fr", sentLanguage("fr"))
        assertEquals("es", sentLanguage("es"))
        assertEquals("en", sentLanguage("en"))
    }

    @Test
    fun `reads the language per request so a change in Profile applies at once`() {
        var language = "en"
        val interceptor = AcceptLanguageInterceptor { language }
        val first = FakeChain()
        interceptor.intercept(first).close()
        language = "fr"
        val second = FakeChain()
        interceptor.intercept(second).close()
        assertEquals("en", first.proceeded.single().header("Accept-Language"))
        assertEquals("fr", second.proceeded.single().header("Accept-Language"))
    }

    @Test
    fun `an unsupported or blank stored value goes out as English`() {
        assertEquals("en", sentLanguage("de"))
        assertEquals("en", sentLanguage(""))
        assertEquals("fr", sentLanguage(" FR "))
    }

    @Test
    fun `replaces a header already on the request instead of sending two`() {
        val original = Request.Builder().url("https://example.invalid/api/ping")
            .header("Accept-Language", "de-DE").build()
        val chain = FakeChain(original)
        AcceptLanguageInterceptor { "fr" }.intercept(chain).close()
        assertEquals(listOf("fr"), chain.proceeded.single().headers("Accept-Language"))
    }
}
