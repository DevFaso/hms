package com.bitnesttechs.hms.patient.labresults.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class LabResultsRepository @Inject constructor(
    private val api: LabResultsApi
) {
    suspend fun getLabResults(): Result<List<LabResultDto>> = try {
        Result.Success(api.getLabResults())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load lab results")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LabResultsModule {
    @Provides
    @Singleton
    fun provideLabResultsApi(retrofit: Retrofit): LabResultsApi =
        retrofit.create(LabResultsApi::class.java)
}
