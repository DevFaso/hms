package com.example.hms.mapper;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestValidationStudy;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class LabTestValidationStudyMapper {

    public LabTestValidationStudy toEntity(LabTestValidationStudyRequestDTO dto,
                                           LabTestDefinition definition) {
        if (dto == null || definition == null) return null;

        return LabTestValidationStudy.builder()
                .labTestDefinition(definition)
                .studyType(dto.getStudyType())
                .studyDate(dto.getStudyDate())
                .performedByUserId(dto.getPerformedByUserId())
                .performedByDisplay(dto.getPerformedByDisplay())
                .summary(dto.getSummary())
                .resultData(dto.getResultData())
                .passed(Boolean.TRUE.equals(dto.getPassed()))
                .notes(dto.getNotes())
                .build();
    }

    public LabTestValidationStudyResponseDTO toDto(LabTestValidationStudy entity) {
        if (entity == null) return null;

        LabTestDefinition def = entity.getLabTestDefinition();
        return LabTestValidationStudyResponseDTO.builder()
                .id(entity.getId())
                .labTestDefinitionId(def != null ? def.getId() : null)
                .testCode(def != null ? def.getTestCode() : null)
                .testName(def != null ? def.getName() : null)
                .studyType(entity.getStudyType() != null ? entity.getStudyType().name() : null)
                .studyDate(entity.getStudyDate())
                .performedByUserId(entity.getPerformedByUserId())
                .performedByDisplay(entity.getPerformedByDisplay())
                .summary(entity.getSummary())
                .resultData(entity.getResultData())
                .passed(entity.isPassed())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public void updateEntityFromDto(LabTestValidationStudyRequestDTO dto,
                                     LabTestValidationStudy entity) {
        if (dto == null || entity == null) return;

        if (dto.getStudyType() != null) entity.setStudyType(dto.getStudyType());
        if (dto.getStudyDate() != null) entity.setStudyDate(dto.getStudyDate());
        entity.setPerformedByUserId(dto.getPerformedByUserId());
        entity.setPerformedByDisplay(dto.getPerformedByDisplay());
        entity.setSummary(dto.getSummary());
        entity.setResultData(dto.getResultData());
        if (dto.getPassed() != null) entity.setPassed(dto.getPassed());
        entity.setNotes(dto.getNotes());
    }
}
