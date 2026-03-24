package com.bitnesttechs.hms.patient.careteam.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class CareTeamRepository @Inject constructor(
    private val api: CareTeamApi
) {
    suspend fun getCareTeam(): Result<List<CareTeamMemberDto>> = try {
        Result.Success(api.getCareTeam())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load care team")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CareTeamModule {
    @Provides
    @Singleton
    fun provideCareTeamApi(retrofit: Retrofit): CareTeamApi =
        retrofit.create(CareTeamApi::class.java)
}
