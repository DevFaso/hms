package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.core.models.LogoutRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Sign-out used to call POST /auth/logout AFTER nothing could still
 * authenticate it, so the server session lived until it expired. The tokens
 * are now captured first, sent explicitly, and the local state is cleared
 * whatever the server says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutTest {

    /** A storage whose clearAll really forgets, so a late read would see nothing. */
    private class Session(
        var access: String? = null,
        var refresh: String? = null,
        var oidcAccess: String? = null
    )

    private fun storage(session: Session) = mockk<TokenStorage>(relaxed = true) {
        every { accessToken } answers { session.access }
        every { refreshToken } answers { session.refresh }
        every { oidcAccessToken } answers { session.oidcAccess }
        every { isLoggedIn } answers { session.access != null || session.oidcAccess != null }
        every { clearAll() } answers { session.access = null; session.refresh = null; session.oidcAccess = null }
    }

    private val api = mockk<ApiService>()
    private val keycloak = mockk<KeycloakAuthService>(relaxed = true)
    private val push = mockk<com.bitnesttechs.hms.patient.core.push.PushRegistrar>(relaxed = true)

    private fun TestScope.repo(storage: TokenStorage) = AuthRepository(api, storage, keycloak, push, this)

    @Test
    fun `password session - one logout with the captured bearer and refresh token, then local state is gone`() = runTest {
        val session = Session(access = "access-jwt", refresh = "refresh-jwt")
        val storage = storage(session)
        coEvery { api.logout(any(), any()) } returns Response.success(Unit)

        repo(storage).logout()
        advanceUntilIdle()

        coVerify(exactly = 1) { api.logout("Bearer access-jwt", LogoutRequest("refresh-jwt")) }
        verify { storage.clearAll() }
        assertNull(session.access)
        coVerify(exactly = 0) { keycloak.revoke(any()) }
    }

    @Test
    fun `SSO session - HMS logout carries the Keycloak bearer without a refresh token, then Keycloak revokes`() = runTest {
        val session = Session(oidcAccess = "kc-access")
        val storage = storage(session)
        val revocation = KeycloakAuthService.Revocation("https://kc.example/revoke", "kc-refresh", "hms-patient-android")
        every { keycloak.pendingRevocation() } returns revocation
        coEvery { api.logout(any(), any()) } returns Response.success(Unit)

        repo(storage).logout()
        advanceUntilIdle()

        coVerify(exactly = 1) { api.logout("Bearer kc-access", LogoutRequest(null)) }
        coVerify(exactly = 1) { keycloak.revoke(revocation) }
        verify { storage.clearAll() }
    }

    @Test
    fun `a refused or unreachable server still leaves the device signed out`() = runTest {
        val session = Session(access = "access-jwt", refresh = "refresh-jwt")
        val storage = storage(session)
        coEvery { api.logout(any(), any()) } returns Response.error(
            401, "{}".toResponseBody("application/json".toMediaType())
        )
        repo(storage).logout()
        advanceUntilIdle()
        assertNull(session.access)

        val offline = Session(access = "a2", refresh = "r2")
        coEvery { api.logout(any(), any()) } throws IOException("offline")
        repo(storage(offline)).logout()
        advanceUntilIdle()
        assertNull(offline.access)
    }

    @Test
    fun `the push device is unbound first, with the same captured bearer, then the session is revoked`() = runTest {
        val session = Session(access = "access-jwt", refresh = "refresh-jwt")
        coEvery { api.logout(any(), any()) } returns Response.success(Unit)

        repo(storage(session)).logout()
        advanceUntilIdle()

        io.mockk.coVerifyOrder {
            push.unregister("Bearer access-jwt")
            api.logout("Bearer access-jwt", LogoutRequest("refresh-jwt"))
        }
    }

    @Test
    fun `a push unbind that fails never stops the logout`() = runTest {
        val session = Session(access = "access-jwt", refresh = "refresh-jwt")
        coEvery { push.unregister(any()) } throws IllegalStateException("boom")
        coEvery { api.logout(any(), any()) } returns Response.success(Unit)
        repo(storage(session)).logout()
        advanceUntilIdle()
        // The registrar is silent by contract; even if it threw, the session is still revoked.
        assertNull(session.access)
        coVerify(exactly = 1) { api.logout("Bearer access-jwt", LogoutRequest("refresh-jwt")) }
    }

    @Test
    fun `no session means no server call`() = runTest {
        repo(storage(Session())).logout()
        advanceUntilIdle()
        coVerify(exactly = 0) { api.logout(any(), any()) }
    }

    @Test
    fun `the Keycloak revocation is the RFC 7009 form post`() {
        val request = KeycloakAuthService.revocationRequest(
            KeycloakAuthService.Revocation("https://kc.example/realms/hms/revoke", "kc-refresh", "hms-patient-android")
        )
        assertEquals("POST", request.method)
        assertEquals("https://kc.example/realms/hms/revoke", request.url.toString())
        assertNull(request.header("Authorization"))
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(
            mapOf("token" to "kc-refresh", "token_type_hint" to "refresh_token", "client_id" to "hms-patient-android"),
            fields
        )
    }
}
