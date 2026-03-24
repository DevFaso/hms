package com.bitnesttechs.hms.patient.vitals.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class VitalsRepository @Inject constructor(
    private val api: VitalsApi
) {
    suspend fun getVitals(): Result<List<VitalDto>> = try {
        Result.Success(api.getVitals())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load vitals")
    }

    suspend fun recordVital(request: RecordVitalRequest): Result<Unit> = try {
        api.recordVital(request)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to record vital")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object VitalsModule {
    @Provides
    @Singleton
    fun provideVitalsApi(retrofit: Retrofit): VitalsApi =
        retrofit.create(VitalsApi::class.java)
}
