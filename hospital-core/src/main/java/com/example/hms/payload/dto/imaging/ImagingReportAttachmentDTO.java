package com.example.hms.payload.dto.imaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportAttachmentDTO {

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

    private LocalDateTime createdAt;
}
