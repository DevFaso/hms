package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chat wire contract.
 *
 * The iOS app shipped for months decoding a shape the backend never sent —
 * `sentAt` instead of `timestamp`, a `thread` concept that does not exist —
 * and nobody noticed because the call sites swallowed the failure and the
 * screen merely looked empty. These tests decode a payload shaped exactly
 * like `ChatConversationSummaryDTO` / `ChatMessageResponseDTO` so a rename on
 * either side fails here instead of silently blanking the Messages tab.
 */
class ChatModelsTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `conversation summary decodes the fields the backend actually sends`() {
        val json = """
            {
              "conversationUserId": "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
              "conversationUserName": "Dr Ouedraogo",
              "lastMessageContent": "Vos resultats sont prets.",
              "lastMessageTimestamp": "2026-09-19T10:30:00",
              "hospitalId": "11111111-2222-3333-4444-555555555555",
              "lastMessageRead": false,
              "unreadCount": 3
            }
        """.trimIndent()

        val dto = moshi.adapter(ChatConversationDto::class.java).fromJson(json)!!

        assertEquals("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f", dto.conversationUserId)
        assertEquals("Dr Ouedraogo", dto.conversationUserName)
        assertEquals("Vos resultats sont prets.", dto.lastMessageContent)
        assertEquals(3, dto.unreadCount)
        assertTrue(!dto.lastMessageRead)
    }

    @Test
    fun `message decodes timestamp, not sentAt`() {
        val json = """
            {
              "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "timestamp": "2026-09-19T10:30:00",
              "senderId": "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
              "senderName": "Dr Ouedraogo",
              "senderRole": "ROLE_DOCTOR",
              "recipientId": "99999999-8888-7777-6666-555555555555",
              "recipientName": "Awa Traore",
              "content": "Vos resultats sont prets.",
              "read": false
            }
        """.trimIndent()

        val dto = moshi.adapter(ChatMessageDto::class.java).fromJson(json)!!

        assertEquals("2026-09-19T10:30:00", dto.timestamp)
        assertEquals("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f", dto.senderId)
        assertEquals("Vos resultats sont prets.", dto.content)
        assertTrue(!dto.read)
    }

    @Test
    fun `send request carries the recipient id the endpoint requires`() {
        val json = moshi.adapter(SendChatMessageRequest::class.java).toJson(
            SendChatMessageRequest(
                recipientId = "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
                content = "Merci docteur."
            )
        )

        // POST /chat/send is keyed by recipientId; a thread id would 400.
        assertTrue(json.contains("\"recipientId\""))
        assertTrue(json.contains("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"))
        assertTrue(json.contains("Merci docteur."))
    }
}
