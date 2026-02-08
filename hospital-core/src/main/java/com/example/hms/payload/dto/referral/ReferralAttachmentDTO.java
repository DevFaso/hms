package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ReferralAttachmentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralAttachmentDTO {
    private UUID id;
    private String storageKey;
    private String displayName;
    private ReferralAttachmentCategory category;
    private String contentType;
    private Long sizeBytes;
    private UUID uploadedBy;
    private String uploadedByDisplayName;
    private LocalDateTime uploadedAt;
}
