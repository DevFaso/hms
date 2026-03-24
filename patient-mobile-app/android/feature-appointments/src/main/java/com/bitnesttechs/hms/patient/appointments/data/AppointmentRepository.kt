package com.bitnesttechs.hms.patient.appointments.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class AppointmentRepository @Inject constructor(
    private val api: AppointmentApi
) {
    suspend fun getAppointments(): Result<List<AppointmentDto>> {
        return try {
            Result.Success(api.getAppointments())
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to load appointments")
        }
    }

    suspend fun cancelAppointment(appointmentId: String): Result<Unit> {
        return try {
            api.cancelAppointment(CancelRequest(appointmentId))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to cancel appointment")
        }
    }

    suspend fun rescheduleAppointment(appointmentId: String, newDateTime: String): Result<Unit> {
        return try {
            api.rescheduleAppointment(RescheduleRequest(appointmentId, newDateTime))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to reschedule appointment")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppointmentModule {
    @Provides
    @Singleton
    fun provideAppointmentApi(retrofit: Retrofit): AppointmentApi =
        retrofit.create(AppointmentApi::class.java)
}
