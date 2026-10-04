package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatMessageResponseDTO.attachments` was never decoded, so a clinician's
 * wound photo or voice note rendered as an empty bubble. And `content` was a
 * non-null String: an attachment-only message (content null, which
 * `ChatMessageServiceImpl.sendMessage` allows) failed the WHOLE history
 * decode, blanking the thread.
 */
class ChatAttachmentModelsTest {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ChatMessageDto::class.java)

    @Test
    fun `attachments decode with their kind, size and duration`() {
        val dto = adapter.fromJson(
            """
            {
              "id": "m1", "timestamp": "2026-09-19T10:30:00",
              "senderId": "doc", "recipientId": "me",
              "content": "Voici la photo",
              "read": false,
              "attachments": [
                {"id": "a1", "storageKey": "k1", "displayName": "plaie.jpg", "contentType": "image/jpeg",
                 "sizeBytes": 183422, "sha256": "ab", "kind": "PHOTO", "durationSeconds": null},
                {"id": "a2", "displayName": "note.m4a", "contentType": "audio/mp4",
                 "sizeBytes": 50211, "kind": "AUDIO", "durationSeconds": 42}
              ]
            }
            """.trimIndent()
        )!!

        assertEquals(2, dto.attachmentList.size)
        val photo = dto.attachmentList[0]
        assertEquals("a1", photo.id)
        assertEquals(ChatAttachmentKind.PHOTO, photo.kindEnum)
        assertEquals(183422L, photo.sizeBytes)
        assertNull(photo.durationSeconds)
        val audio = dto.attachmentList[1]
        assertEquals(ChatAttachmentKind.AUDIO, audio.kindEnum)
        assertEquals(42, audio.durationSeconds)
        assertEquals("Voici la photo", dto.text)
    }

    @Test
    fun `an attachment-only message decodes, with no text to draw`() {
        val dto = adapter.fromJson(
            """
            {"id": "m2", "timestamp": "2026-09-19T10:31:00", "senderId": "doc", "recipientId": "me",
             "content": null, "read": true,
             "attachments": [{"id": "a3", "contentType": "image/png", "sizeBytes": 10, "kind": "PHOTO"}]}
            """.trimIndent()
        )!!
        assertNull(dto.text)
        assertEquals(listOf("a3"), dto.attachmentList.map { it.id })
    }

    @Test
    fun `a blank content is no text either`() {
        val dto = adapter.fromJson("""{"id": "m3", "senderId": "doc", "content": "  ", "attachments": []}""")!!
        assertNull(dto.text)
    }

    @Test
    fun `explicit null or absent attachments mean none, and old payloads still decode`() {
        assertTrue(adapter.fromJson("""{"id": "m4", "content": "hi", "attachments": null}""")!!.attachmentList.isEmpty())
        assertTrue(adapter.fromJson("""{"id": "m5", "content": "hi"}""")!!.attachmentList.isEmpty())
        assertNull(adapter.fromJson("""{"id": "m6", "content": "hi", "timestamp": null}""")!!.timestamp)
    }

    @Test
    fun `an unknown kind is offered as a file instead of failing`() {
        assertEquals(ChatAttachmentKind.OTHER, ChatAttachmentKind.fromWire("VIDEO"))
        assertEquals(ChatAttachmentKind.OTHER, ChatAttachmentKind.fromWire(null))
        assertEquals(ChatAttachmentKind.OTHER, ChatAttachmentKind.fromWire("OTHER"))
        assertEquals(ChatAttachmentKind.PHOTO, ChatAttachmentKind.fromWire("photo"))
    }
}
