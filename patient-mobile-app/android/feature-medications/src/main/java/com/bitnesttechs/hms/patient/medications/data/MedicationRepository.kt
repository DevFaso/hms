package com.bitnesttechs.hms.patient.medications.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class MedicationRepository @Inject constructor(
    private val api: MedicationApi
) {
    suspend fun getMedications(): Result<List<MedicationDto>> = try {
        Result.Success(api.getMedications())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load medications")
    }

    suspend fun getPrescriptions(): Result<List<PrescriptionDto>> = try {
        Result.Success(api.getPrescriptions())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load prescriptions")
    }

    suspend fun getRefills(): Result<List<RefillDto>> = try {
        Result.Success(api.getRefills())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load refills")
    }

    suspend fun requestRefill(prescriptionId: String): Result<Unit> = try {
        api.requestRefill(RefillRequest(prescriptionId))
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to request refill")
    }

    suspend fun cancelRefill(id: String): Result<Unit> = try {
        api.cancelRefill(id)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to cancel refill")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object MedicationModule {
    @Provides
    @Singleton
    fun provideMedicationApi(retrofit: Retrofit): MedicationApi =
        retrofit.create(MedicationApi::class.java)
}
