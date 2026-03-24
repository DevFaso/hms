package com.bitnesttechs.hms.patient.auth.data

import com.bitnesttechs.hms.patient.core.security.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    suspend fun login(username: String, password: String): Result<UserDto>
    suspend fun logout()
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApi,
    private val tokenManager: TokenManager
) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<UserDto> {
        return try {
            val response = api.login(LoginRequest(username, password))
            tokenManager.setAccessToken(response.resolvedAccessToken)
            response.refreshToken?.let { tokenManager.setRefreshToken(it) }
            Result.success(response.user ?: UserDto("", username))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout() {
        try {
            api.logout()
        } finally {
            tokenManager.clearTokens()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideAuthRepository(impl: AuthRepositoryImpl): AuthRepository = impl
}
