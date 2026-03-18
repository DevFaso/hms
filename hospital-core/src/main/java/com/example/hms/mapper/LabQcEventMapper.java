package com.example.hms.mapper;

import com.example.hms.model.LabQcEvent;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class LabQcEventMapper {

    public LabQcEventResponseDTO toResponseDTO(LabQcEvent event) {
        if (event == null) return null;
        return LabQcEventResponseDTO.builder()
            .id(event.getId())
            .hospitalId(event.getHospitalId())
            .analyzerId(event.getAnalyzerId())
            .testDefinitionId(event.getTestDefinition() != null ? event.getTestDefinition().getId() : null)
            .testDefinitionName(event.getTestDefinition() != null ? event.getTestDefinition().getName() : null)
            .qcLevel(event.getQcLevel() != null ? event.getQcLevel().name() : null)
            .measuredValue(event.getMeasuredValue())
            .expectedValue(event.getExpectedValue())
            .passed(event.isPassed())
            .recordedAt(event.getRecordedAt())
            .recordedById(event.getRecordedById())
            .notes(event.getNotes())
            .createdAt(event.getCreatedAt())
            .build();
    }
}
