package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.LoginRequest
import com.bitnesttechs.hms.patient.core.models.LoginResponse
import com.bitnesttechs.hms.patient.core.models.SessionBootstrapDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Both sign-in paths persist the HMS `users.id` resolved by
 * `/auth/session/bootstrap`: before this an SSO session never stored one, so
 * chat (`/chat/conversations/{userId}`) reported nothing to a signed-in
 * patient and the history notes were filed under the Keycloak sub.
 */
class SessionBootstrapTest {

    private val tokenStorage = mockk<TokenStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val repo = AuthRepository(api, tokenStorage, mockk(relaxed = true), mockk(relaxed = true), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), com.bitnesttechs.hms.patient.core.network.SessionCookieJar())

    private val hmsUserId = "0b6d8c0e-0000-4000-8000-00000000abcd"

    private fun bootstrap(userId: String? = hmsUserId, roles: List<String> = listOf("ROLE_PATIENT")) =
        Response.success(SessionBootstrapDto(userId = userId, roles = roles, firstName = "Awa"))

    private fun passwordLogin(id: String = hmsUserId) = Response.success(
        LoginResponse(accessToken = "access", refreshToken = "refresh", id = id, roles = listOf("ROLE_PATIENT"))
    )

    @Test
    fun `password sign-in persists the user id the bootstrap resolves`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns passwordLogin(id = hmsUserId)
        coEvery { api.getSessionBootstrap() } returns bootstrap()

        val result = repo.login("awa", "pw", saveCredentials = false)

        assertEquals(AuthResult.Success, result)
        coVerify(exactly = 1) { api.getSessionBootstrap() }
        verify { tokenStorage.userId = hmsUserId }
    }

    @Test
    fun `password sign-in keeps the login body user id when the bootstrap is unreachable`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns passwordLogin(id = hmsUserId)
        coEvery { api.getSessionBootstrap() } throws IOException("offline")

        val result = repo.login("awa", "pw", saveCredentials = false)

        assertEquals(AuthResult.Success, result)
        verify { tokenStorage.userId = hmsUserId }
        verify(exactly = 0) { tokenStorage.clearAll() }
    }

    @Test
    fun `SSO sign-in persists the HMS user id, not the Keycloak subject`() = runTest {
        coEvery { api.getSessionBootstrap() } returns bootstrap()

        val result = repo.completeSignIn(sso = true)

        assertEquals(AuthResult.Success, result)
        verify { tokenStorage.userId = hmsUserId }
        assertEquals(hmsUserId, repo.currentUser.value?.id)
    }

    @Test
    fun `SSO sign-in whose bootstrap fails ends the session instead of entering without a user id`() = runTest {
        coEvery { api.getSessionBootstrap() } returns Response.error(
            500, "{}".toResponseBody("application/json".toMediaType())
        )

        val result = repo.completeSignIn(sso = true)

        assertTrue(result is AuthResult.Error)
        assertEquals(R.string.login_error_session, (result as AuthResult.Error).messageRes)
        verify(exactly = 0) { tokenStorage.userId = any() }
        verify { tokenStorage.clearAll() }
    }

    @Test
    fun `SSO sign-in by a non-patient account is refused like the password one`() = runTest {
        coEvery { api.getSessionBootstrap() } returns bootstrap(roles = listOf("ROLE_DOCTOR"))

        val result = repo.completeSignIn(sso = true)

        assertEquals(R.string.login_error_patients_only, (result as AuthResult.Error).messageRes)
        verify { tokenStorage.clearAll() }
        verify(exactly = 0) { tokenStorage.userId = any() }
    }

    @Test
    fun `an existing session without a user id resolves it once at start`() = runTest {
        every { tokenStorage.isLoggedIn } returns true
        every { tokenStorage.userId } returns null
        coEvery { api.getSessionBootstrap() } returns bootstrap()

        repo.ensureUserId()

        verify { tokenStorage.userId = hmsUserId }
    }

    @Test
    fun `a session that already has its user id does not call the bootstrap again`() = runTest {
        every { tokenStorage.isLoggedIn } returns true
        every { tokenStorage.userId } returns hmsUserId

        repo.ensureUserId()

        coVerify(exactly = 0) { api.getSessionBootstrap() }
    }
}
