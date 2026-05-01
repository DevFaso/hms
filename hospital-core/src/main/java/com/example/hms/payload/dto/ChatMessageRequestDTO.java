package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatMessageRequestDTO {
    /** Recipient email – used by hospital-context sends. Optional if recipientId is set. */
    private String recipientEmail;

    /** Hospital name – used by hospital-context sends. Optional for SUPER_ADMIN. */
    private String hospitalName;

    /**
     * Message body. May be blank or null for attachment-only sends; the service enforces
     * "content-or-attachments required" so an empty message with no attachments is rejected.
     */
    private String content;

    private String roleCode;

    /**
     * Deprecated: ignored by the current messaging implementation.
     * The sender is always derived from the SecurityContext.
     * @deprecated since 1.0, forRemoval in a future release.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private UUID senderId;

    /** Recipient UUID – alternative to recipientEmail. */
    private UUID recipientId;

    /**
     * Optional telehealth attachments. Each entry must carry the {@code storageKey}
     * returned by {@code POST /files/chat-attachments}; service re-resolves and
     * persists them under the new message. Capped at 4 by the service.
     */
    private List<ChatAttachmentDTO> attachments;
}
