package com.bitnesttechs.hms.patient.consents.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class ConsentsRepository @Inject constructor(
    private val api: ConsentsApi
) {
    suspend fun getConsents(): Result<List<ConsentDto>> = try {
        Result.Success(api.getConsents())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load consents")
    }

    suspend fun grantConsent(request: GrantConsentRequest): Result<Unit> = try {
        api.grantConsent(request)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to grant consent")
    }

    suspend fun revokeConsent(fromId: String, toId: String): Result<Unit> = try {
        api.revokeConsent(fromId, toId)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to revoke consent")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ConsentsModule {
    @Provides
    @Singleton
    fun provideConsentsApi(retrofit: Retrofit): ConsentsApi =
        retrofit.create(ConsentsApi::class.java)
}
