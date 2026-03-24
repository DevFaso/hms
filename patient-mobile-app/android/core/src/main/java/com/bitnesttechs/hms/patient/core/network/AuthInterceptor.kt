package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.core.security.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches the Bearer token to every request
 * and handles 401 → token refresh → retry.
 */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth header for login/register endpoints
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/login") || path.contains("/auth/register")) {
            return chain.proceed(originalRequest)
        }

        val token = tokenManager.getAccessToken()
        val authedRequest = if (token != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(authedRequest)

        // On 401, try refresh once
        if (response.code == 401 && token != null) {
            response.close()

            val refreshed = runBlocking { tokenManager.refreshToken() }
            if (refreshed) {
                val newToken = tokenManager.getAccessToken() ?: return response
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retryRequest)
            } else {
                tokenManager.clearTokens()
            }
        }

        return response
    }
}
