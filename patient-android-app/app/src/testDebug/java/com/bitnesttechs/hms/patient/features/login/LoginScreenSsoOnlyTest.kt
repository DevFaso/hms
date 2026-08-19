package com.bitnesttechs.hms.patient.features.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.bitnesttechs.hms.patient.TestApplication
import com.bitnesttechs.hms.patient.core.auth.AuthRepository
import com.bitnesttechs.hms.patient.core.auth.AuthResult
import com.bitnesttechs.hms.patient.core.auth.KeycloakAuthService
import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.config.FeatureFlagManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * KC-3 / Phase 2.2 (G-6) — UI-level coverage of the cutover state where
 * the backend has flipped `app.auth.oidc.required=true` and now returns
 * **HTTP 410 Gone** on `/api/auth/login`. The Login screen must:
 *
 *   1. Surface the SSO button (flag ON + issuer configured), and
 *   2. Steer any legacy username/password attempt toward SSO instead of
 *      the generic "Login failed. Please try again." copy.
 *
 * Runs as a debug-variant Robolectric Compose UI test so it executes in CI
 * without an emulator and has access to Compose's test activity manifest.
 * Mirrors the Playwright `keycloak-login.spec.ts` describe block on the
 * portal side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [33])
class LoginScreenSsoOnlyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val legacyDisabledMessage =
        "Legacy username/password login is disabled. Sign in via Single Sign-On."

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cutover state surfaces SSO button and routes legacy login to the SSO copy`() {
        val featureFlagManager = mockk<FeatureFlagManager> {
            every { keycloakSsoEnabled } returns flowOf(true)
        }
        val keycloakAuthService = mockk<KeycloakAuthService> {
            every { isConfigured } returns true
        }
        val authRepository = mockk<AuthRepository> {
            coEvery {
                login(any(), any(), any())
            } returns AuthResult.Error(legacyDisabledMessage)
        }
        val tokenStorage = mockk<TokenStorage>(relaxed = true) {
            every { savedUsername } returns null
        }

        val viewModel = LoginViewModel(authRepository, featureFlagManager, keycloakAuthService)

        composeTestRule.setContent {
            LoginScreen(
                tokenStorage = tokenStorage,
                onLoginSuccess = {},
                viewModel = viewModel,
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Sign in with SSO")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Sign in with SSO")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Username or Email").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("hunter2")
        composeTestRule.onAllNodesWithText("Sign In")
            .filterToOne(hasClickAction())
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ShadowToast.getTextOfLatestToast() != null
        }
        val toastText = ShadowToast.getTextOfLatestToast()
        assertNotNull("expected an error toast to be shown after legacy login", toastText)
        assertTrue(
            "expected SSO-pointing toast, got: $toastText",
            toastText.contains("SSO", ignoreCase = true) ||
                toastText.contains("Single Sign-On", ignoreCase = true),
        )
    }
}
