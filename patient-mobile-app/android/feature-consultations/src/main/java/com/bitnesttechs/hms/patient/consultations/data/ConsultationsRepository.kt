package com.bitnesttechs.hms.patient.consultations.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class ConsultationsRepository @Inject constructor(
    private val api: ConsultationsApi
) {
    suspend fun getConsultations() = api.getConsultations()
}

@Module
@InstallIn(SingletonComponent::class)
object ConsultationsModule {
    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): ConsultationsApi =
        retrofit.create(ConsultationsApi::class.java)
}
