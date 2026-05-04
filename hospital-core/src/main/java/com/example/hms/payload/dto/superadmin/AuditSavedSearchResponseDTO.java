package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Server-side audit saved-search snapshot (MVP-c batch — MVP-8c).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Persisted audit-search saved query.")
public class AuditSavedSearchResponseDTO {

    private UUID id;
    private String ownerUsername;
    private String name;
    private String filterJson;
    private boolean shared;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
