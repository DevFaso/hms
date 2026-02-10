package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ReferralAttachmentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralAttachmentUploadResponseDTO {
    private String tempFileId;
    private String publicUrl;
    private String displayName;
    private ReferralAttachmentCategory category;
    private String contentType;
    private Long sizeBytes;
}
