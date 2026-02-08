package com.example.hms.payload.dto.referral;

import com.example.hms.enums.ReferralAttachmentCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralAttachmentUploadDTO {
    @NotBlank
    private String tempFileId;

    @NotBlank
    private String displayName;

    @NotNull
    private ReferralAttachmentCategory category;

    private String contentType;
    private Long sizeBytes;
}
