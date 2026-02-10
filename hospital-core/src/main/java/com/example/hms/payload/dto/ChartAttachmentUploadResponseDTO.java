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
@Schema(description = "Response payload returned after uploading a chart attachment")
public class ChartAttachmentUploadResponseDTO {

    @Schema(description = "Storage key referencing the persisted attachment", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storageKey;

    @Schema(description = "Original filename if available")
    private String fileName;

    @Schema(description = "Clinician facing label applied to the attachment")
    private String label;

    @Schema(description = "Optional clinical category tag")
    private String category;

    @Schema(description = "Attachment MIME type")
    private String contentType;

    @Schema(description = "Size in bytes")
    private Long sizeBytes;

    @Schema(description = "SHA-256 checksum for integrity validation")
    private String sha256;

    @Schema(description = "Signed or public URL for downloading the attachment")
    private String downloadUrl;
}
