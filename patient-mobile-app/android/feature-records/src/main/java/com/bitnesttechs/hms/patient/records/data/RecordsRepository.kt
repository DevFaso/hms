package com.bitnesttechs.hms.patient.records.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class RecordsRepository @Inject constructor(
    private val api: RecordsApi
) {
    suspend fun getEncounters(): Result<List<EncounterDto>> = try {
        Result.Success(api.getEncounters())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load encounters")
    }

    suspend fun getAfterVisitSummaries(): Result<List<VisitSummaryDto>> = try {
        Result.Success(api.getAfterVisitSummaries())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load visit summaries")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RecordsModule {
    @Provides
    @Singleton
    fun provideRecordsApi(retrofit: Retrofit): RecordsApi =
        retrofit.create(RecordsApi::class.java)
}
