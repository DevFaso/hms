package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2ExportStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record Dhis2ExportRunResponseDTO(
    UUID id,
    UUID hospitalId,
    String datasetUid,
    String periodIso,
    UUID triggeredByStaffId,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    Dhis2ExportStatus status,
    int valueCount,
    int skippedCount,
    Integer httpStatus,
    String errorMessage,
    UUID requestId
) { }
