package com.bitnesttechs.hms.patient.core.auth

import com.bitnesttechs.hms.patient.core.models.RefreshTokenRequest
import kotlinx.coroutines.runBlocking
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
    private val moshi: Moshi
) : Interceptor {

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
        val accessToken = tokenStorage.accessToken
        val request = chain.request().newBuilder().apply {
            if (accessToken != null) {
                header("Authorization", "Bearer $accessToken")
            }
        }.build()

        val response = chain.proceed(request)

        // Auto-refresh on 401
        if (response.code == 401 && tokenStorage.refreshToken != null) {
            response.close()
            val refreshed = runBlocking { tryRefresh() }
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
