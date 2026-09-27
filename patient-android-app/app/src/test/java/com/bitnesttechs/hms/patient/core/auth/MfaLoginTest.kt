package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.LoginRequest
import com.bitnesttechs.hms.patient.core.models.LoginResponse
import com.bitnesttechs.hms.patient.core.models.MfaVerifyRequest
import com.bitnesttechs.hms.patient.core.models.SessionBootstrapDto
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * A login answered with `mfaRequired` used to fail to decode at all
 * (accessToken was a required field and the challenge carries none). It now
 * becomes a challenge, and /auth/mfa/verify's body is accepted like a login.
 */
class MfaLoginTest {

    private val tokenStorage = mockk<TokenStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val repo = AuthRepository(api, tokenStorage, mockk(relaxed = true), mockk(relaxed = true), CoroutineScope(Dispatchers.Unconfined))

    private val challenge = LoginResponse(mfaRequired = true, mfaEnrolled = true, mfaToken = "mfa-jwt", username = "awa")
    private val tokens = LoginResponse(accessToken = "access", refreshToken = "refresh", id = "u1", roles = listOf("ROLE_PATIENT"))

    @Test
    fun `an MFA challenge is returned as such and stores no token`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.success(challenge)

        val result = repo.login("awa", "pw", saveCredentials = true)

        assertEquals(AuthResult.MfaRequired("mfa-jwt", enrolled = true), result)
        verify(exactly = 0) { tokenStorage.accessToken = any() }
        verify(exactly = 0) { tokenStorage.savedPassword = any() }
    }

    @Test
    fun `an account without a factor is told so, not asked for a code`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.success(challenge.copy(mfaEnrolled = false))
        assertEquals(AuthResult.MfaRequired("mfa-jwt", enrolled = false), repo.login("awa", "pw", false))
    }

    @Test
    fun `a verified code is accepted like a login and remembers the credentials asked for`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.success(challenge)
        coEvery { api.verifyMfa(any()) } returns Response.success(tokens)
        coEvery { api.getSessionBootstrap() } returns Response.success(SessionBootstrapDto(userId = "u1", roles = listOf("ROLE_PATIENT")))

        repo.login("awa", "pw", saveCredentials = true)
        val result = repo.verifyMfa("mfa-jwt", " 123456 ")

        assertEquals(AuthResult.Success, result)
        coVerify { api.verifyMfa(MfaVerifyRequest("mfa-jwt", "123456")) }
        verify { tokenStorage.accessToken = "access" }
        verify { tokenStorage.refreshToken = "refresh" }
        verify { tokenStorage.userId = "u1" }
        verify { tokenStorage.savedUsername = "awa" }
        verify { tokenStorage.savedPassword = "pw" }
    }

    @Test
    fun `a wrong code is a localized refusal with the server's sentence`() = runTest {
        coEvery { api.verifyMfa(any()) } returns Response.error(
            401, """{"message":"Code MFA invalide."}""".toResponseBody("application/json".toMediaType())
        )
        val result = repo.verifyMfa("mfa-jwt", "000000") as AuthResult.Error
        assertEquals(R.string.mfa_error_invalid_code, result.messageRes)
        assertEquals("Code MFA invalide.", result.detail)
        verify(exactly = 0) { tokenStorage.accessToken = any() }
    }

    @Test
    fun `a wrong password offers the activation flow, since an inactive account answers the same 401`() = runTest {
        coEvery { api.login(any<LoginRequest>()) } returns Response.error(
            401, """{"message":"x"}""".toResponseBody("application/json".toMediaType())
        )
        val result = repo.login("awa", "pw", false) as AuthResult.Error
        assertEquals(true, result.offerActivation)
    }
}
