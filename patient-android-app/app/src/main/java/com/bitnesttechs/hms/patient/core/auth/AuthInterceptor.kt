package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.core.models.RefreshTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import com.bitnesttechs.hms.patient.BuildConfig
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.squareup.moshi.Moshi

/**
 * OkHttp interceptor that:
 * 1. Injects Authorization: Bearer <accessToken> on every request
 * 2. On 401: attempts one token refresh, then retries the original request
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val moshi: Moshi,
    private val keycloakAuthServiceProvider: Provider<KeycloakAuthService>
) : Interceptor {

    private val refreshMutex = Mutex()
    private val oidcRefreshMutex = Mutex()

    // Lazy to break circular Hilt dependency (ApiService → OkHttp → AuthInterceptor → ApiService)
    private val refreshService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(
                okhttp3.OkHttpClient.Builder().build() // plain client — no interceptor
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Prefer the Keycloak OIDC access token when a Keycloak session is active
        // (KC-3). Falls back to the legacy username/password access token otherwise.
        val oidcToken = tokenStorage.oidcAccessToken
        val legacyToken = tokenStorage.accessToken
        val accessToken = oidcToken ?: legacyToken
        val usingOidc = oidcToken != null
        val request = chain.request().newBuilder().apply {
            if (accessToken != null) {
                header("Authorization", "Bearer $accessToken")
            }
        }.build()

        val response = chain.proceed(request)

        if (response.code != 401) {
            return response
        }

        if (usingOidc) {
            // OIDC path: ask AppAuth to refresh via AuthState#performActionWithFreshTokens
            // and retry the request once. A null token means the refresh failed (e.g.
            // refresh token expired) and the caller will see the original 401.
            val tokenBefore = oidcToken
            val refreshedToken = runBlocking(Dispatchers.IO) {
                oidcRefreshMutex.withLock {
                    val current = tokenStorage.oidcAccessToken
                    if (current != null && current != tokenBefore) {
                        // Another thread already refreshed while we waited.
                        current
                    } else {
                        keycloakAuthServiceProvider.get().freshAccessToken()
                    }
                }
            }
            if (refreshedToken != null && refreshedToken != tokenBefore) {
                response.close()
                val retryRequest = chain.request().newBuilder()
                    .header("Authorization", "Bearer $refreshedToken")
                    .build()
                return chain.proceed(retryRequest)
            }
            return response
        }

        // Legacy (password-grant) refresh path.
        if (tokenStorage.refreshToken != null) {
            response.close()
            val tokenBefore = legacyToken
            val refreshed = runBlocking(Dispatchers.IO) {
                refreshMutex.withLock {
                    // If another thread already refreshed while we waited, skip
                    if (tokenStorage.accessToken != tokenBefore && tokenStorage.accessToken != null) {
                        true
                    } else {
                        tryRefresh()
                    }
                }
            }
            if (refreshed) {
                val retryRequest = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${tokenStorage.accessToken}")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    private suspend fun tryRefresh(): Boolean {
        val refreshToken = tokenStorage.refreshToken ?: return false
        return try {
            val resp = refreshService.refreshToken(RefreshTokenRequest(refreshToken))
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                tokenStorage.accessToken = body.accessToken
                body.refreshToken?.let { tokenStorage.refreshToken = it }
                true
            } else {
                tokenStorage.clearAll()
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
