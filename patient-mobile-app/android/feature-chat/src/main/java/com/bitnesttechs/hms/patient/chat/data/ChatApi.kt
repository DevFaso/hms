package com.bitnesttechs.hms.patient.chat.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ChatApi {

    @GET("chat/conversations/{userId}")
    suspend fun getConversations(@Path("userId") userId: String): List<ConversationDto>

    @GET("chat/history/{user1Id}/{user2Id}")
    suspend fun getChatHistory(
        @Path("user1Id") user1Id: String,
        @Path("user2Id") user2Id: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): List<ChatMessageDto>

    @POST("chat/send")
    suspend fun sendMessage(@Body request: SendMessageRequest)

    @PUT("chat/mark-read/{senderId}/{recipientId}")
    suspend fun markAsRead(@Path("senderId") senderId: String, @Path("recipientId") recipientId: String)
}

@Serializable
data class ConversationDto(
    val id: String = "",
    val participantId: String? = null,
    val participantName: String? = null,
    val participantRole: String? = null,
    val lastMessage: String? = null,
    val lastMessageTime: String? = null,
    val unreadCount: Int? = null,
    val avatarUrl: String? = null
)

@Serializable
data class ChatMessageDto(
    val id: String = "",
    val senderId: String? = null,
    val recipientId: String? = null,
    val content: String? = null,
    val timestamp: String? = null,
    val isRead: Boolean? = null,
    val senderName: String? = null
)

@Serializable
data class SendMessageRequest(
    val senderId: String,
    val recipientId: String,
    val content: String
)
