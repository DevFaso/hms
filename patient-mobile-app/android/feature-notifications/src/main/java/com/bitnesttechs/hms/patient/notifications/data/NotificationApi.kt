package com.bitnesttechs.hms.patient.notifications.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface NotificationApi {

    @GET("notifications")
    suspend fun getNotifications(): List<NotificationDto>

    @PUT("notifications/{id}/read")
    suspend fun markAsRead(@Path("id") id: String)

    @POST("notifications/device-token")
    suspend fun registerDeviceToken(@Body body: DeviceTokenRequest)
}

@Serializable
data class NotificationDto(
    val id: String = "",
    val title: String? = null,
    val message: String? = null,
    val type: String? = null,
    val read: Boolean? = null,
    val createdAt: String? = null,
    val relatedEntityType: String? = null,
    val relatedEntityId: String? = null
)

@Serializable
data class DeviceTokenRequest(val token: String, val platform: String = "android")
