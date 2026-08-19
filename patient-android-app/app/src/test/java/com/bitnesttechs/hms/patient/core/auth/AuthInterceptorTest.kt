package com.bitnesttechs.hms.patient.core.auth

import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KC-3: verifies that when a Keycloak OIDC access token is present it takes
 * precedence over the legacy username/password access token on the
 * `Authorization` header.
 */
class AuthInterceptorTest {

    private val moshi = Moshi.Builder().build()
    private val keycloakProvider: Provider<KeycloakAuthService> = Provider { mockk(relaxed = true) }

    @Test
    fun `prefers OIDC token over legacy token`() {
        val storage = mockk<TokenStorage>(relaxed = true)
        every { storage.oidcAccessToken } returns "oidc-xyz"
        every { storage.accessToken } returns "legacy-abc"
        every { storage.refreshToken } returns null

        val interceptor = AuthInterceptor(storage, moshi, keycloakProvider)
        val header = captureAuthHeader(interceptor)

        assertEquals("Bearer oidc-xyz", header)
    }

    @Test
    fun `falls back to legacy token when no OIDC token`() {
        val storage = mockk<TokenStorage>(relaxed = true)
        every { storage.oidcAccessToken } returns null
        every { storage.accessToken } returns "legacy-abc"
        every { storage.refreshToken } returns null

        val interceptor = AuthInterceptor(storage, moshi, keycloakProvider)
        val header = captureAuthHeader(interceptor)

        assertEquals("Bearer legacy-abc", header)
    }

    @Test
    fun `sends no Authorization header when no tokens present`() {
        val storage = mockk<TokenStorage>(relaxed = true)
        every { storage.oidcAccessToken } returns null
        every { storage.accessToken } returns null
        every { storage.refreshToken } returns null

        val interceptor = AuthInterceptor(storage, moshi, keycloakProvider)
        val header = captureAuthHeader(interceptor)

        assertEquals(null, header)
    }

    private fun captureAuthHeader(interceptor: AuthInterceptor): String? {
        var captured: String? = null
        val chain = object : Interceptor.Chain {
            private val original = Request.Builder().url("https://example.invalid/api/v1/ping").build()
            override fun request(): Request = original
            override fun proceed(request: Request): Response {
                captured = request.header("Authorization")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain).close()
        return captured
    }
}
