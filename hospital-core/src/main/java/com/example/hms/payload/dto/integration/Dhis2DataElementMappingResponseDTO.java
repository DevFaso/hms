package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2PeriodType;
import java.time.LocalDateTime;
import java.util.UUID;

public record Dhis2DataElementMappingResponseDTO(
    UUID id,
    UUID hospitalId,
    String hmsConceptSystem,
    String hmsConceptCode,
    String dhis2DataElementUid,
    String dhis2CategoryOptionComboUid,
    Dhis2PeriodType periodType,
    String datasetUid,
    boolean active,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
