package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Shared base class for imaging report attachment fields,
 * eliminating duplication between request and response DTOs.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class ImagingReportAttachmentBaseDTO {

    private Integer position;

    private String storageKey;

    private String storageBucket;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    private String dicomObjectUid;

    private String viewerUrl;

    private String thumbnailKey;

    private String label;

    private String category;
}
