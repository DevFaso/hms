package com.bitnesttechs.hms.patient.accesslogs.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class AccessLogsRepository @Inject constructor(
    private val api: AccessLogsApi
) {
    suspend fun getAccessLog(page: Int = 0): Result<List<AccessLogEntryDto>> = try {
        Result.Success(api.getAccessLog(page))
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load access logs")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AccessLogsModule {
    @Provides
    @Singleton
    fun provideAccessLogsApi(retrofit: Retrofit): AccessLogsApi =
        retrofit.create(AccessLogsApi::class.java)
}
