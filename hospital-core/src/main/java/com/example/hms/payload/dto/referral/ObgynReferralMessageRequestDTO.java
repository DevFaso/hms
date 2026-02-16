package com.example.hms.payload.dto.referral;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralMessageRequestDTO {
    @NotBlank
    private String body;

    @Builder.Default
    @Valid
    private List<MessageAttachmentDTO> attachments = List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageAttachmentDTO {
        @NotBlank
        private String tempFileId;

        private String displayName;
        private String contentType;
        private Long sizeBytes;
    }
}
