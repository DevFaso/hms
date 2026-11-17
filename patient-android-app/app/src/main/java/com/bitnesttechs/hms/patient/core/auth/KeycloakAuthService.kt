package com.bitnesttechs.hms.patient.core.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bitnesttechs.hms.patient.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Keycloak / OIDC auth façade backed by AppAuth-Android (KC-3).
 *
 * This service is additive — it does not replace [AuthRepository]. While
 * `FeatureFlagManager.keycloakSsoEnabled` is false (the default until
 * prerequisite P-2 lands), nothing in the app uses this class.
 *
 * Lifecycle:
 *  - [buildAuthorizationIntent] returns an Intent that the Activity launches
 *    through the AppAuth `ActivityResultContract` / Custom Tabs flow.
 *  - [handleAuthorizationResponse] consumes the result Intent and completes
 *    the authorization code + PKCE token exchange.
 *  - [freshAccessToken] returns a (possibly refreshed) access token for the
 *    OkHttp interceptor.
 *  - [buildEndSessionIntent] assembles an RP-initiated logout Intent.
 */
@Singleton
class KeycloakAuthService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStorage: TokenStorage
) {
    private val authorizationService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    private val redirectUri: Uri = Uri.parse(BuildConfig.KEYCLOAK_REDIRECT_URI)

    /** True when the build has a non-empty issuer configured. */
    val isConfigured: Boolean
        get() = BuildConfig.KEYCLOAK_ISSUER.isNotBlank()

    /**
     * Discover the OIDC endpoints for the configured issuer.
     *
     * @throws IllegalStateException if `BuildConfig.KEYCLOAK_ISSUER` is blank.
     */
    suspend fun fetchConfiguration(): AuthorizationServiceConfiguration {
        check(isConfigured) {
            "Keycloak issuer is not configured (BuildConfig.KEYCLOAK_ISSUER is empty)"
        }
        val issuerUri = Uri.parse(BuildConfig.KEYCLOAK_ISSUER)
        return suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(issuerUri) { config, ex ->
                if (config != null) {
                    cont.resume(config)
                } else {
                    cont.resumeWith(
                        Result.failure(ex ?: IllegalStateException("Unknown discovery error"))
                    )
                }
            }
        }
    }

    /**
     * Build an authorization (login) Intent for the Activity to launch.
     * Uses Auth Code + PKCE.
     */
    suspend fun buildAuthorizationIntent(): Intent {
        val config = fetchConfiguration()
        val request = AuthorizationRequest.Builder(
            config,
            BuildConfig.KEYCLOAK_CLIENT_ID,
            ResponseTypeValues.CODE,
            redirectUri
        )
            .setScope("openid profile email offline_access")
            .build()

        // Persist a stub AuthState tied to this config so later calls can reuse it
        saveAuthState(AuthState(config))
        return authorizationService.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchange the authorization response for tokens and persist the resulting
     * [AuthState]. Returns the completed AuthState on success.
     */
    suspend fun handleAuthorizationResponse(data: Intent): AuthState {
        val response = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (response == null) {
            throw ex ?: IllegalStateException("AuthorizationResponse was null")
        }

        val state = loadAuthState() ?: AuthState(response, ex)
        state.update(response, ex)

        val tokenResponse = exchangeCode(response)
        state.update(tokenResponse, null)
        saveAuthState(state)
        return state
    }

    private suspend fun exchangeCode(response: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { cont ->
            authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { token, ex ->
                if (token != null) {
                    cont.resume(token)
                } else {
                    cont.resumeWith(
                        Result.failure(ex ?: IllegalStateException("Token exchange failed"))
                    )
                }
            }
        }

    /**
     * Returns a non-expired access token, refreshing via the refresh token when
     * necessary. Returns null when no OIDC session is active.
     */
    suspend fun freshAccessToken(): String? {
        val state = loadAuthState() ?: return null
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authorizationService) { token, _, ex ->
                if (ex != null) {
                    cont.resume(null)
                    return@performActionWithFreshTokens
                }
                saveAuthState(state)
                cont.resume(token)
            }
        }
    }

    /**
     * Build an RP-initiated end-session Intent for the Activity to launch.
     * Returns null when there is no OIDC session or the id token has been
     * discarded.
     */
    fun buildEndSessionIntent(postLogoutRedirect: Uri = redirectUri): Intent? {
        val state = loadAuthState() ?: return null
        val config = state.authorizationServiceConfiguration ?: return null
        val idToken = state.idToken ?: return null
        val request = EndSessionRequest.Builder(config)
            .setIdTokenHint(idToken)
            .setPostLogoutRedirectUri(postLogoutRedirect)
            .build()
        return authorizationService.getEndSessionRequestIntent(request)
    }

    /** A refresh token to revoke at Keycloak, captured before the session is cleared. */
    data class Revocation(val endpoint: String, val refreshToken: String, val clientId: String)

    /**
     * What [revoke] needs, read from the stored AuthState: the refresh token
     * and the `revocation_endpoint` of the discovery document. Null when
     * there is no SSO session or the provider publishes no such endpoint.
     */
    fun pendingRevocation(): Revocation? {
        val state = loadAuthState() ?: return null
        val refreshToken = state.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val endpoint = state.authorizationServiceConfiguration
            ?.discoveryDoc
            ?.docJson
            ?.optString("revocation_endpoint")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Revocation(endpoint, refreshToken, BuildConfig.KEYCLOAK_CLIENT_ID)
    }

    /**
     * The HMS logout cannot revoke a Keycloak token, so an SSO sign-out also
     * posts the refresh token to Keycloak (RFC 7009). Best effort: the
     * caller ignores the outcome. Sent on a bare client — the API client
     * would attach the patient's access token to a request for another host.
     */
    suspend fun revoke(revocation: Revocation) {
        withContext(Dispatchers.IO) {
            revocationClient.newCall(revocationRequest(revocation)).execute().close()
        }
    }

    private val revocationClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun clear() {
        tokenStorage.clearOidc()
    }

    fun dispose() {
        authorizationService.dispose()
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    internal fun saveAuthState(state: AuthState) {
        tokenStorage.oidcAuthStateJson = state.jsonSerializeString()
        tokenStorage.oidcAccessToken = state.accessToken
        tokenStorage.oidcIdToken = state.idToken
    }

    internal fun loadAuthState(): AuthState? {
        val json = tokenStorage.oidcAuthStateJson ?: return null
        return try {
            AuthState.jsonDeserialize(json)
        } catch (_: org.json.JSONException) {
            null
        }
    }

    companion object {
        /** The RFC 7009 request: form-encoded token, hint and public client id. */
        fun revocationRequest(revocation: Revocation): Request =
            Request.Builder()
                .url(revocation.endpoint)
                .post(
                    FormBody.Builder()
                        .add("token", revocation.refreshToken)
                        .add("token_type_hint", "refresh_token")
                        .add("client_id", revocation.clientId)
                        .build()
                )
                .build()
    }

    /** PendingIntent helpers for future integration with launchAuthorizationRequest(). */
    @Suppress("unused")
    internal fun pendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
