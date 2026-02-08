package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for referral attachments
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferralAttachmentResponseDTO {

    private UUID id;
    private UUID referralId;
    private String storageKey;
    private String displayName;
    private String category;
    private String contentType;
    private Long sizeBytes;
    
    private UUID uploadedById;
    private String uploadedByName;
    
    private LocalDateTime uploadedAt;
    private String description;
}
