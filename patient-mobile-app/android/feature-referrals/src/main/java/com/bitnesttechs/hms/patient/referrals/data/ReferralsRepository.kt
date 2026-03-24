package com.bitnesttechs.hms.patient.referrals.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class ReferralsRepository @Inject constructor(
    private val api: ReferralsApi
) {
    suspend fun getReferrals() = api.getReferrals()
}

@Module
@InstallIn(SingletonComponent::class)
object ReferralsModule {
    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): ReferralsApi =
        retrofit.create(ReferralsApi::class.java)
}
