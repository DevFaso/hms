package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.core.models.LoginRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * KC-3 cutover (S-03 / G-6): when the backend flips
 * {@code app.auth.oidc.required=true}, {@code /auth/login} returns
 * **HTTP 410 Gone** with body
 * {@code {"message":"Legacy username/password login is disabled. Sign in via Single Sign-On."}}.
 *
 * <p>This suite asserts that {@link AuthRepository} surfaces that
 * message verbatim instead of leaking a raw JSON blob, so the user
 * sees actionable copy and is steered toward the SSO button.</p>
 */
class AuthRepositoryTest {

    private val tokenStorage = mockk<TokenStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val repo = AuthRepository(api, tokenStorage)

    private val legacyDisabledMessage =
        "Legacy username/password login is disabled. Sign in via Single Sign-On."

    @Test
    fun `410 with message body surfaces the runbook copy verbatim`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.error(
            410,
            """{"message":"$legacyDisabledMessage"}""".toResponseBody("application/json".toMediaType())
        )

        val result = repo.login("any.user", "any.password", saveCredentials = false)

        assertTrue("expected Error, got $result", result is AuthResult.Error)
        assertEquals(legacyDisabledMessage, (result as AuthResult.Error).message)
    }

    @Test
    fun `410 with empty body falls back to a sane SSO-pointing default`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.error(
            410,
            "".toResponseBody("application/json".toMediaType())
        )

        val result = repo.login("any.user", "any.password", saveCredentials = false)

        assertTrue(result is AuthResult.Error)
        val message = (result as AuthResult.Error).message
        // The user must still be steered toward SSO, not shown a generic "try again".
        assertTrue(
            "expected SSO-pointing fallback, got: $message",
            message.contains("SSO", ignoreCase = true) ||
                    message.contains("Single sign-on", ignoreCase = true)
        )
    }

    @Test
    fun `401 still surfaces the canonical Invalid credentials copy`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.error(
            401,
            """{"message":"some-other-thing"}""".toResponseBody("application/json".toMediaType())
        )

        val result = repo.login("any.user", "any.password", saveCredentials = false)

        assertTrue(result is AuthResult.Error)
        assertEquals("Invalid username or password", (result as AuthResult.Error).message)
    }

    @Test
    fun `parseErrorMessage extracts message field from Spring-style body`() {
        val parsed = repo.parseErrorMessage("""{"message":"$legacyDisabledMessage"}""")
        assertEquals(legacyDisabledMessage, parsed)
    }

    @Test
    fun `parseErrorMessage falls back to error field when message is absent`() {
        val parsed = repo.parseErrorMessage("""{"error":"validation failed"}""")
        assertEquals("validation failed", parsed)
    }

    @Test
    fun `parseErrorMessage returns null on null, blank, or unparseable input`() {
        assertNull(repo.parseErrorMessage(null))
        assertNull(repo.parseErrorMessage(""))
        assertNull(repo.parseErrorMessage("   "))
        assertNull(repo.parseErrorMessage("not json at all"))
        // Empty object is parseable but has neither field — still null.
        assertNull(repo.parseErrorMessage("{}"))
    }
}
