package com.example.hms.payload.dto.referral;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralMessageDTO {
    private UUID id;
    private UUID senderUserId;
    private String senderDisplayName;
    private String body;
    private boolean read;
    private LocalDateTime sentAt;
    private List<MessageAttachmentDTO> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageAttachmentDTO {
        private String storageKey;
        private String displayName;
        private String contentType;
        private Long sizeBytes;
    }
}
