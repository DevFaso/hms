package com.example.hms.payload.dto.reference;

import com.example.hms.enums.ReferenceCatalogStatus;
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
public class ReferenceCatalogResponseDTO {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private ReferenceCatalogStatus status;
    private int entryCount;
    private LocalDateTime publishedAt;
    private LocalDateTime scheduledPublishAt;
    private LocalDateTime lastImportedAt;
}
