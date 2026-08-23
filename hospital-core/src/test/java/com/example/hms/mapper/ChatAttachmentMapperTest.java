package com.example.hms.mapper;

import com.example.hms.enums.ChatAttachmentKind;
import com.example.hms.model.ChatAttachment;
import com.example.hms.payload.dto.ChatAttachmentDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatAttachmentMapperTest {

    private final ChatAttachmentMapper mapper = new ChatAttachmentMapper();

    @Test
    void toDto_returnsNullForNullEntity() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void toDto_copiesAllFieldsForPhoto() {
        UUID id = UUID.randomUUID();
        ChatAttachment entity = ChatAttachment.builder()
            .id(id)
            .kind(ChatAttachmentKind.PHOTO)
            .storageKey("/uploads/chat-attachments/x.jpg")
            .publicUrl("https://hms/uploads/chat-attachments/x.jpg")
            .displayName("x.jpg")
            .contentType("image/jpeg")
            .sizeBytes(2048L)
            .sha256("cafe")
            .durationSeconds(null)
            .build();

        ChatAttachmentDTO dto = mapper.toDto(entity);

        assertEquals(id, dto.getId());
        assertEquals(ChatAttachmentKind.PHOTO, dto.getKind());
        assertEquals("/uploads/chat-attachments/x.jpg", dto.getStorageKey());
        // Derived, never the stored public_url — historical rows carry
        // absolute /uploads URLs from the permitAll era; clients fetch
        // through the authenticated, participant-checked endpoint.
        assertEquals("/chat/attachments/" + id + "/download", dto.getPublicUrl());
        assertEquals("x.jpg", dto.getDisplayName());
        assertEquals("image/jpeg", dto.getContentType());
        assertEquals(2048L, dto.getSizeBytes());
        assertEquals("cafe", dto.getSha256());
        assertNull(dto.getDurationSeconds());
    }

    @Test
    void toDto_carriesDurationForAudio() {
        ChatAttachment entity = ChatAttachment.builder()
            .id(UUID.randomUUID())
            .kind(ChatAttachmentKind.AUDIO)
            .storageKey("/uploads/chat-attachments/v.opus")
            .publicUrl("https://hms/uploads/chat-attachments/v.opus")
            .displayName("voice_memo.opus")
            .contentType("audio/ogg")
            .sizeBytes(80000L)
            .sha256("beef")
            .durationSeconds(45)
            .build();

        ChatAttachmentDTO dto = mapper.toDto(entity);

        assertEquals(ChatAttachmentKind.AUDIO, dto.getKind());
        assertEquals(45, dto.getDurationSeconds());
    }
}
