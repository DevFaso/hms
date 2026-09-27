package com.bitnesttechs.hms.patient.features.messages

import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Opening a thread marks it read (PUT /chat/mark-read/{sender}/{recipient},
 * 204): sender = the other party, recipient = the signed-in patient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageThreadMarkReadTest {

    private val me = "patient-user-id"
    private val clinician = "clinician-user-id"
    private val api = mockk<ApiService>()
    private val tokenStorage = mockk<TokenStorage> { every { userId } returns me }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening a thread marks the other party's messages to me read`() = runTest {
        coEvery { api.getChatHistory(me, clinician, any(), any()) } returns Response.success(emptyList())
        coEvery { api.markChatRead(any(), any()) } returns Response.success(204, Unit)

        val vm = MessageThreadViewModel(api, tokenStorage)
        vm.loadThread(clinician)

        coVerify(exactly = 1) { api.markChatRead(senderId = clinician, recipientId = me) }
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `a failed mark-read never breaks the thread`() = runTest {
        coEvery { api.getChatHistory(me, clinician, any(), any()) } returns Response.success(emptyList())
        coEvery { api.markChatRead(any(), any()) } returns Response.error(
            500, "{}".toResponseBody("application/json".toMediaType())
        )

        val vm = MessageThreadViewModel(api, tokenStorage)
        vm.loadThread(clinician)

        assertFalse(vm.isLoading.value)
        coVerify(exactly = 1) { api.markChatRead(clinician, me) }
    }

    @Test
    fun `no signed-in user id means no mark-read call`() = runTest {
        every { tokenStorage.userId } returns null
        val vm = MessageThreadViewModel(api, tokenStorage)
        vm.loadThread(clinician)
        coVerify(exactly = 0) { api.markChatRead(any(), any()) }
    }
}
