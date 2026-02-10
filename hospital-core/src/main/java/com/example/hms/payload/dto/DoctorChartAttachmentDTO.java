package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Metadata describing an attachment referenced by a chart update.")
public class DoctorChartAttachmentDTO {

    @NotBlank
    @Schema(description = "Storage key or URL reference for the attachment", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storageKey;

    @Schema(description = "Original filename presented to clinicians")
    private String fileName;

    @Schema(description = "Attachment MIME type")
    private String contentType;

    @Schema(description = "Size in bytes, when known")
    private Long sizeBytes;

    @Schema(description = "Optional checksum for integrity validation")
    private String sha256;

    @Schema(description = "Human-friendly label applied in the UI")
    private String label;

    @Schema(description = "Category (e.g. LAB, IMAGING, SOCIAL_SUPPORT)")
    private String category;
}
