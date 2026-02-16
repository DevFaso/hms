package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Attachment metadata returned as part of a patient chart update history entry.")
public class PatientChartAttachmentResponseDTO {

    @Schema(description = "Storage key or URL reference for the attachment")
    private String storageKey;

    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String label;
    private String category;
}
