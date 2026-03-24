package com.bitnesttechs.hms.patient.notifications.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class NotificationRepository @Inject constructor(
    private val api: NotificationApi
) {
    suspend fun getNotifications(): Result<List<NotificationDto>> = try {
        Result.Success(api.getNotifications())
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load notifications")
    }

    suspend fun markAsRead(id: String): Result<Unit> = try {
        api.markAsRead(id)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to mark notification as read")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {
    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi =
        retrofit.create(NotificationApi::class.java)
}
