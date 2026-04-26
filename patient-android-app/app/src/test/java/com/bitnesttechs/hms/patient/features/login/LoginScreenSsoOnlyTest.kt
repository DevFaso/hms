package com.bitnesttechs.hms.patient.features.login

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Runs as a Robolectric Compose UI test so it executes in CI without an
 * emulator. Mirrors the Playwright `keycloak-login.spec.ts` describe
 * block on the portal side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [33])
class LoginScreenSsoOnlyTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
        // Given: cutover-state collaborators — flag ON, issuer configured,
        // backend would return 410 on legacy login.
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

        // When: the login screen renders
        composeTestRule.setContent {
            LoginScreen(
                tokenStorage = tokenStorage,
                onLoginSuccess = {},
                viewModel = viewModel,
            )
        }

        // Wait for the StateFlow-backed `ssoEnabled` to propagate through
        // `collectAsState()` and Compose's recomposition before asserting.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Sign in with SSO")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Then: SSO button is offered up-front (the cutover affordance).
        // Scroll first because the login screen is taller than the test
        // viewport — the button is below the fold by default.
        composeTestRule.onNodeWithText("Sign in with SSO")
            .performScrollTo()
            .assertIsDisplayed()

        // And: when the user still tries the legacy form, the screen
        // surfaces the runbook copy that points at SSO — never the
        // generic "Login failed. Please try again." fallback.
        composeTestRule.onNodeWithText("Username or Email").performTextInput("alice")
        composeTestRule.onNodeWithText("Password").performTextInput("hunter2")
        composeTestRule.onAllNodesWithText("Sign In")
            .filterToOne(hasClickAction())
            .performScrollTo()
            .performClick()

        // The screen pushes errors through `Toast.makeText(...).show()` (driven
        // by a `LaunchedEffect(uiState.error)` that runs on Compose's recomposer
        // dispatcher), then clears `uiState.error`. We wait for the toast to
        // appear rather than reading state directly, because state has already
        // been cleared by the time the assertion runs.
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
