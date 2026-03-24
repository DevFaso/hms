package com.bitnesttechs.hms.patient.home.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HomeModule {
    @Provides
    @Singleton
    fun providePatientPortalApi(retrofit: Retrofit): PatientPortalApi =
        retrofit.create(PatientPortalApi::class.java)
}
