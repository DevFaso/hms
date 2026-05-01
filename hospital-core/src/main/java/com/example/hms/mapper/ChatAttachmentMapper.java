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
            .publicUrl(attachment.getPublicUrl())
            .displayName(attachment.getDisplayName())
            .contentType(attachment.getContentType())
            .sizeBytes(attachment.getSizeBytes())
            .sha256(attachment.getSha256())
            .kind(attachment.getKind())
            .durationSeconds(attachment.getDurationSeconds())
            .build();
    }
}
