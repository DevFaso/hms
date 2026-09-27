package com.bitnesttechs.hms.patient.features.account

import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.auth.AccountRepository
import com.bitnesttechs.hms.patient.core.auth.AccountResult
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.auth.AuthResult
import com.bitnesttechs.hms.patient.core.auth.KeycloakAuthService
import com.bitnesttechs.hms.patient.core.config.FeatureFlagManager
import com.bitnesttechs.hms.patient.core.models.ChangePasswordRequest
import com.bitnesttechs.hms.patient.core.models.PasswordResetConfirm
import com.bitnesttechs.hms.patient.core.models.PasswordResetRequest
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.bitnesttechs.hms.patient.features.login.LoginViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Parity phase 3: forgot password, activation, MFA challenge, change password. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountFlowsTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val api = mockk<ApiService>()
    private val accounts = AccountRepository(api)
    private fun json(code: Int, body: String = "{}") =
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType()))

    // ── Links pasted from the e-mail ───────────────────────────────────────

    @Test
    fun `a pasted link or a bare token both yield the token`() {
        assertEquals("abc-123", AccountRepository.fromLink("https://e-keneya.com/reset-password?token=abc-123", "token"))
        assertEquals("abc-123", AccountRepository.fromLink("  abc-123 ", "token"))
        assertEquals("tok", AccountRepository.fromLink("https://dev.e-keneya.com/verify?email=awa@x.bf&token=tok", "token"))
        assertEquals("awa+1@x.bf", AccountRepository.fromLink("https://dev.e-keneya.com/verify?email=awa+1@x.bf&token=tok", "email"))
        assertNull(AccountRepository.fromLink("abc-123", "email"))
        assertNull(AccountRepository.fromLink("   ", "token"))
        assertNull(AccountRepository.fromLink("https://e-keneya.com/reset-password", "token"))
    }

    // ── Forgot password ────────────────────────────────────────────────────

    @Test
    fun `asking for a reset link is neutral whatever the backend says`() = runTest {
        coEvery { api.requestPasswordReset(any()) } returns Response.success(204, Unit)
        val vm = ForgotPasswordViewModel(accounts)
        vm.onEmail(" awa@x.bf ")
        vm.requestLink()
        assertTrue(vm.state.value.linkSent)
        coVerify { api.requestPasswordReset(PasswordResetRequest("awa@x.bf")) }

        coEvery { api.requestPasswordReset(any()) } returns json(404)
        val vm2 = ForgotPasswordViewModel(accounts)
        vm2.onEmail("nobody@x.bf")
        vm2.requestLink()
        assertTrue(vm2.state.value.linkSent)
    }

    @Test
    fun `no address means no request`() = runTest {
        val vm = ForgotPasswordViewModel(accounts)
        vm.requestLink()
        assertEquals(R.string.enter_email, vm.state.value.error?.messageRes)
        coVerify(exactly = 0) { api.requestPasswordReset(any()) }
    }

    @Test
    fun `an unreachable server is reported, not dressed up as sent`() = runTest {
        coEvery { api.requestPasswordReset(any()) } throws IOException("offline")
        val vm = ForgotPasswordViewModel(accounts)
        vm.onEmail("awa@x.bf")
        vm.requestLink()
        assertFalse(vm.state.value.linkSent)
        assertEquals(R.string.login_error_unreachable, vm.state.value.error?.messageRes)
    }

    @Test
    fun `the reset confirms with the token taken from the pasted link`() = runTest {
        coEvery { api.confirmPasswordReset(any()) } returns Response.success(204, Unit)
        val vm = ForgotPasswordViewModel(accounts)
        vm.onCode("https://e-keneya.com/reset-password?token=raw-token")
        vm.onNewPassword("n3w-passw0rd")
        vm.onConfirmPassword("n3w-passw0rd")
        vm.confirm()
        coVerify { api.confirmPasswordReset(PasswordResetConfirm("raw-token", "n3w-passw0rd")) }
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `the reset refuses a short or mismatched password and a missing code before any request`() = runTest {
        val vm = ForgotPasswordViewModel(accounts)
        vm.onNewPassword("12345678"); vm.onConfirmPassword("12345678")
        vm.confirm()
        assertEquals(R.string.reset_code_missing, vm.state.value.error?.messageRes)
        vm.onCode("tok"); vm.onNewPassword("short"); vm.onConfirmPassword("short")
        vm.confirm()
        assertEquals(R.string.password_min_length, vm.state.value.error?.messageRes)
        vm.onNewPassword("long-enough-1"); vm.onConfirmPassword("long-enough-2")
        vm.confirm()
        assertEquals(R.string.passwords_mismatch, vm.state.value.error?.messageRes)
        coVerify(exactly = 0) { api.confirmPasswordReset(any()) }
    }

    // ── Activation ─────────────────────────────────────────────────────────

    @Test
    fun `the activation link carries both the address and the token`() = runTest {
        coEvery { api.verifyEmail(any(), any()) } returns Response.success(Unit)
        val vm = ActivationViewModel(accounts)
        vm.onLink("https://e-keneya.com/verify?email=awa@x.bf&token=act-1")
        vm.activate()
        coVerify { api.verifyEmail("awa@x.bf", "act-1") }
        assertTrue(vm.state.value.activated)
    }

    @Test
    fun `a bare token uses the typed address, and a refused link says so`() = runTest {
        coEvery { api.verifyEmail(any(), any()) } returns json(400)
        val vm = ActivationViewModel(accounts)
        vm.onEmail("awa@x.bf")
        vm.onLink("act-1")
        vm.activate()
        coVerify { api.verifyEmail("awa@x.bf", "act-1") }
        assertFalse(vm.state.value.activated)
        assertEquals(R.string.activation_failed, vm.state.value.error?.messageRes)
    }

    @Test
    fun `resending activation is neutral`() = runTest {
        coEvery { api.resendVerification(any()) } returns Response.success(Unit)
        val vm = ActivationViewModel(accounts)
        vm.onEmail("awa@x.bf")
        vm.resend()
        coVerify { api.resendVerification("awa@x.bf") }
        assertTrue(vm.state.value.linkSent)
    }

    // ── Change password ────────────────────────────────────────────────────

    private fun changeVm(current: String, new: String, confirm: String = new) =
        ChangePasswordViewModel(accounts).apply { onCurrent(current); onNewPassword(new); onConfirmPassword(confirm) }

    @Test
    fun `change password posts the two fields and succeeds`() = runTest {
        coEvery { api.changePassword(any()) } returns Response.success(Unit)
        val vm = changeVm("old-passw0rd", "new-passw0rd")
        vm.submit()
        coVerify { api.changePassword(ChangePasswordRequest("old-passw0rd", "new-passw0rd")) }
        assertTrue(vm.state.value.done)
        assertEquals("", vm.state.value.current)
    }

    @Test
    fun `a wrong current password and a refused new one read differently`() = runTest {
        coEvery { api.changePassword(any()) } returns json(401)
        val wrong = changeVm("bad-current", "new-passw0rd")
        wrong.submit()
        assertEquals(R.string.change_password_wrong_current, wrong.state.value.error?.messageRes)

        coEvery { api.changePassword(any()) } returns
            json(400, """{"message":"Le nouveau mot de passe ne doit pas correspondre aux 5 derniers."}""")
        val reused = changeVm("old-passw0rd", "reused-passw0rd")
        reused.submit()
        assertEquals(R.string.change_password_failed, reused.state.value.error?.messageRes)
        assertEquals("Le nouveau mot de passe ne doit pas correspondre aux 5 derniers.", reused.state.value.error?.detail)
    }

    @Test
    fun `change password checks the rules locally first`() = runTest {
        changeVm("same-passw0rd", "same-passw0rd").also { it.submit() }.let {
            assertEquals(R.string.change_password_same, it.state.value.error?.messageRes)
        }
        changeVm("old-passw0rd", "short").also { it.submit() }.let {
            assertEquals(R.string.password_min_length, it.state.value.error?.messageRes)
        }
        changeVm("", "new-passw0rd").also { it.submit() }.let {
            assertEquals(R.string.change_password_current_required, it.state.value.error?.messageRes)
        }
        coVerify(exactly = 0) { api.changePassword(any()) }
    }

    // ── MFA challenge on the login screen ──────────────────────────────────

    private fun loginVm(auth: AuthRepository): LoginViewModel {
        val flags = mockk<FeatureFlagManager> { every { keycloakSsoEnabled } returns flowOf(false) }
        val keycloak = mockk<KeycloakAuthService> { every { isConfigured } returns false }
        return LoginViewModel(auth, flags, keycloak)
    }

    @Test
    fun `a challenged sign-in shows the code step, and a good code signs in`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.login(any(), any(), any()) } returns AuthResult.MfaRequired("mfa-jwt", enrolled = true)
        coEvery { auth.verifyMfa("mfa-jwt", "123456") } returns AuthResult.Success
        val vm = loginVm(auth)

        vm.login("awa", "pw")
        assertEquals(AuthResult.MfaRequired("mfa-jwt", true), vm.uiState.value.mfa)
        assertFalse(vm.uiState.value.isSuccess)

        vm.verifyMfa("123456")
        assertTrue(vm.uiState.value.isSuccess)
        assertNull(vm.uiState.value.mfa)
    }

    @Test
    fun `a malformed code is refused before the request, and back returns to the password form`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.login(any(), any(), any()) } returns AuthResult.MfaRequired("mfa-jwt", enrolled = true)
        val vm = loginVm(auth)
        vm.login("awa", "pw")

        vm.verifyMfa("12")
        assertEquals(R.string.mfa_invalid_format, vm.uiState.value.mfaError?.messageRes)
        coVerify(exactly = 0) { auth.verifyMfa(any(), any()) }

        vm.cancelMfa()
        assertNull(vm.uiState.value.mfa)
    }

    @Test
    fun `a refused password points at activation`() = runTest {
        val auth = mockk<AuthRepository>()
        coEvery { auth.login(any(), any(), any()) } returns
            AuthResult.Error(R.string.login_error_invalid_credentials, offerActivation = true)
        val vm = loginVm(auth)
        vm.login("awa", "pw")
        assertTrue(vm.uiState.value.showActivationHint)
    }

    @Test
    fun `account results map every failure to a resource`() = runTest {
        coEvery { api.resendVerification(any()) } throws IOException("offline")
        assertEquals(AccountResult.Failed(R.string.login_error_unreachable), accounts.resendActivation("a@b.c"))
    }
}
