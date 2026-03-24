package com.bitnesttechs.hms.patient.treatmentplans.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class TreatmentPlansRepository @Inject constructor(
    private val api: TreatmentPlansApi
) {
    suspend fun getTreatmentPlans(page: Int, size: Int) = api.getTreatmentPlans(page, size)
}

@Module
@InstallIn(SingletonComponent::class)
object TreatmentPlansModule {
    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): TreatmentPlansApi =
        retrofit.create(TreatmentPlansApi::class.java)
}
