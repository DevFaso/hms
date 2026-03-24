package com.bitnesttechs.hms.patient.chat.data

import com.bitnesttechs.hms.patient.core.network.Result
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

class ChatRepository @Inject constructor(
    private val api: ChatApi
) {
    suspend fun getConversations(userId: String): Result<List<ConversationDto>> = try {
        Result.Success(api.getConversations(userId))
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load conversations")
    }

    suspend fun getChatHistory(user1Id: String, user2Id: String): Result<List<ChatMessageDto>> = try {
        Result.Success(api.getChatHistory(user1Id, user2Id))
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to load messages")
    }

    suspend fun sendMessage(request: SendMessageRequest): Result<Unit> = try {
        api.sendMessage(request)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to send message")
    }

    suspend fun markAsRead(senderId: String, recipientId: String): Result<Unit> = try {
        api.markAsRead(senderId, recipientId)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e.message ?: "Failed to mark as read")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideChatApi(retrofit: Retrofit): ChatApi =
        retrofit.create(ChatApi::class.java)
}
