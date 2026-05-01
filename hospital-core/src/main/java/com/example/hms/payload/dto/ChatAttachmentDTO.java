package com.example.hms.payload.dto;

import com.example.hms.enums.ChatAttachmentKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Wire model for a single chat-message attachment (telehealth low-bandwidth).
 * Used both inbound (POST /chat/send → list of {@code storageKey}s) and
 * outbound (chat history responses → full descriptor for rendering).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatAttachmentDTO {

    private UUID id;

    /** Storage key returned by POST /files/chat-attachments. Required on send. */
    private String storageKey;

    private String publicUrl;
    private String displayName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private ChatAttachmentKind kind;

    /** Audio length in seconds (1..90); null for PHOTO. */
    private Integer durationSeconds;
}
