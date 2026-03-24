package com.bitnesttechs.hms.patient.immunizations.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class ImmunizationsRepository @Inject constructor(
    private val api: ImmunizationsApi
) {
    suspend fun getImmunizations(): Result<List<ImmunizationDto>> = try {
        Result.Success(api.getImmunizations())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load immunizations")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ImmunizationsModule {
    @Provides
    @Singleton
    fun provideImmunizationsApi(retrofit: Retrofit): ImmunizationsApi =
        retrofit.create(ImmunizationsApi::class.java)
}
