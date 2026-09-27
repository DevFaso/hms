package com.bitnesttechs.hms.patient.core.push

import com.bitnesttechs.hms.patient.BuildConfig
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.models.PushDeviceRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Push registration per the contract: PUT /me/push-devices/{installationId}
 * after sign-in and on a new token, DELETE with the captured bearer at
 * sign-out, and nothing at all when the build has no Firebase values. The
 * backend endpoints may not be deployed, so every failure (404/405
 * included) is silent and never retried.
 */
class PushRegistrarTest {

    private class FakeStore : PushInstallationStore {
        var generated = 0
        private var id: String? = null
        override fun installationId(): String = id ?: "install-${++generated}".also { id = it }
        override var permissionAsked: Boolean = false
    }

    private val api = mockk<ApiService>()
    private val tokenStorage = mockk<TokenStorage> { every { isLoggedIn } returns true }
    private val tokenSource = mockk<PushTokenSource> { coEvery { token() } returns "fcm-token-1" }
    private val language = mockk<AppLanguage> { every { current() } returns "fr" }
    private val store = FakeStore()

    private fun config(enabled: Boolean) = object : PushConfig() {
        override val enabled = enabled
    }

    private fun TestScope.registrar(enabled: Boolean = true) =
        PushRegistrar(api, tokenStorage, tokenSource, store, config(enabled), language, this)

    private fun error(code: Int) = Response.error<Unit>(code, "".toResponseBody("application/json".toMediaType()))

    @Test
    fun `registration sends the token, platform, app language and version for this installation`() = runTest {
        coEvery { api.registerPushDevice(any(), any()) } returns Response.success(204, Unit)

        registrar().register()

        coVerify(exactly = 1) {
            api.registerPushDevice(
                "install-1",
                PushDeviceRequest(token = "fcm-token-1", platform = "ANDROID", locale = "fr", appVersion = BuildConfig.VERSION_NAME)
            )
        }
    }

    @Test
    fun `the installation id is generated once and reused`() = runTest {
        coEvery { api.registerPushDevice(any(), any()) } returns Response.success(204, Unit)
        val registrar = registrar()
        registrar.register()
        registrar.register()
        coVerify(exactly = 2) { api.registerPushDevice("install-1", any()) }
        assertEquals(1, store.generated)
    }

    @Test
    fun `a new platform token is registered as handed over, without asking Firebase again`() = runTest {
        coEvery { api.registerPushDevice(any(), any()) } returns Response.success(204, Unit)
        registrar().onNewToken("fcm-token-2")
        advanceUntilIdle()
        coVerify { api.registerPushDevice("install-1", match { it.token == "fcm-token-2" }) }
        coVerify(exactly = 0) { tokenSource.token() }
    }

    @Test
    fun `without the Firebase values nothing is called at all`() = runTest {
        val registrar = registrar(enabled = false)
        registrar.register()
        registrar.registerAsync()
        registrar.onNewToken("x")
        registrar.unregister("Bearer a")
        advanceUntilIdle()
        coVerify(exactly = 0) { api.registerPushDevice(any(), any()) }
        coVerify(exactly = 0) { api.unregisterPushDevice(any(), any()) }
        coVerify(exactly = 0) { tokenSource.token() }
        assertFalse(registrar.shouldAskNotificationPermission())
    }

    @Test
    fun `signed out, there is nothing to bind`() = runTest {
        every { tokenStorage.isLoggedIn } returns false
        registrar().register()
        coVerify(exactly = 0) { api.registerPushDevice(any(), any()) }
    }

    @Test
    fun `no token yet means no call`() = runTest {
        coEvery { tokenSource.token() } returns null
        registrar().register()
        coVerify(exactly = 0) { api.registerPushDevice(any(), any()) }
    }

    @Test
    fun `404, 405 and network failures are silent and tried once`() = runTest {
        val registrar = registrar()
        for (failure in listOf(error(404), error(405), error(500))) {
            coEvery { api.registerPushDevice(any(), any()) } returns failure
            registrar.register()
        }
        coEvery { api.registerPushDevice(any(), any()) } throws IOException("offline")
        registrar.register()
        coEvery { api.unregisterPushDevice(any(), any()) } throws IOException("offline")
        registrar.unregister("Bearer a")
        // Four register calls for four attempts: no retry behind any of them.
        coVerify(exactly = 4) { api.registerPushDevice(any(), any()) }
    }

    @Test
    fun `sign-out unbinds this installation with the explicit bearer`() = runTest {
        coEvery { api.unregisterPushDevice(any(), any()) } returns Response.success(204, Unit)
        registrar().unregister("Bearer captured")
        coVerify(exactly = 1) { api.unregisterPushDevice("Bearer captured", "install-1") }
    }

    @Test
    fun `the notification permission is asked for once`() = runTest {
        val registrar = registrar()
        assertTrue(registrar.shouldAskNotificationPermission())
        registrar.markNotificationPermissionAsked()
        assertFalse(registrar.shouldAskNotificationPermission())
    }

    @Test
    fun `a notification tap routes only chat messages, with a sane sender id`() {
        assertEquals(
            PushTarget("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"),
            PushTarget.fromExtras("CHAT_MESSAGE", "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f")
        )
        assertEquals(PushTarget(null), PushTarget.fromExtras("CHAT_MESSAGE", "../../x"))
        assertEquals(PushTarget(null), PushTarget.fromExtras("CHAT_MESSAGE", null))
        assertEquals(null, PushTarget.fromExtras("OTHER", "abc"))
        assertEquals(null, PushTarget.fromExtras(null, null))
    }
}
