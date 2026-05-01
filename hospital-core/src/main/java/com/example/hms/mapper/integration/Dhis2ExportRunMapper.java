package com.example.hms.mapper.integration;

import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.payload.dto.integration.Dhis2ExportRunResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class Dhis2ExportRunMapper {

    public Dhis2ExportRunResponseDTO toResponseDTO(Dhis2ExportRun entity) {
        if (entity == null) {
            return null;
        }
        return new Dhis2ExportRunResponseDTO(
            entity.getId(),
            entity.getHospital() != null ? entity.getHospital().getId() : null,
            entity.getDatasetUid(),
            entity.getPeriodIso(),
            entity.getTriggeredByStaffId(),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            entity.getStatus(),
            entity.getValueCount(),
            entity.getSkippedCount(),
            entity.getHttpStatus(),
            entity.getErrorMessage(),
            entity.getRequestId()
        );
    }
}
