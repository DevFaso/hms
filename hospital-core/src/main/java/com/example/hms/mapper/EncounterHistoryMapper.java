package com.example.hms.mapper;

import com.example.hms.model.EncounterHistory;
import com.example.hms.payload.dto.EncounterHistoryResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class EncounterHistoryMapper {

    public EncounterHistoryResponseDTO toResponseDto(EncounterHistory history) {
        if (history == null) {
            return null;
        }

        return EncounterHistoryResponseDTO.builder()
            .id(history.getId())
            .encounterId(history.getEncounterId())
            .changedAt(history.getChangedAt())
            .changedBy(history.getChangedBy())
            .encounterType(history.getEncounterType() != null ? history.getEncounterType().name() : null)
            .status(history.getStatus() != null ? history.getStatus().name() : null)
            .encounterDate(history.getEncounterDate())
            .notes(history.getNotes())
            .changeType(history.getChangeType())
            .previousValuesJson(history.getPreviousValuesJson())
            .extraFieldsJson(history.getExtraFieldsJson())
            .build();
    }
}
