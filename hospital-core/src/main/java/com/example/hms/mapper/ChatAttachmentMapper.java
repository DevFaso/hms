package com.example.hms.mapper;

import com.example.hms.model.ChatAttachment;
import com.example.hms.payload.dto.ChatAttachmentDTO;
import org.springframework.stereotype.Component;

@Component
public class ChatAttachmentMapper {

    public ChatAttachmentDTO toDto(ChatAttachment attachment) {
        if (attachment == null) return null;
        return ChatAttachmentDTO.builder()
            .id(attachment.getId())
            .storageKey(attachment.getStorageKey())
            // Derived, never the stored public_url: historical rows carry
            // absolute /uploads URLs from when the tree was served
            // permitAll. Clients fetch through the authenticated,
            // participant-checked download endpoint instead.
            .publicUrl("/chat/attachments/" + attachment.getId() + "/download")
            .displayName(attachment.getDisplayName())
            .contentType(attachment.getContentType())
            .sizeBytes(attachment.getSizeBytes())
            .sha256(attachment.getSha256())
            .kind(attachment.getKind())
            .durationSeconds(attachment.getDurationSeconds())
            .build();
    }
}
