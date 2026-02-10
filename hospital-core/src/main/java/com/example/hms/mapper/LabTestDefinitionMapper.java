package com.example.hms.mapper;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class LabTestDefinitionMapper {

    public LabTestDefinition toEntity(LabTestDefinitionRequestDTO dto, UserRoleHospitalAssignment assignment) {
        if (dto == null) {
            return null;
        }

        LabTestDefinition entity = LabTestDefinition.builder()
            .testCode(dto.getTestCode())
            .name(dto.getName())
            .category(dto.getCategory())
            .description(dto.getDescription())
            .sampleType(dto.getSampleType())
            .unit(dto.getUnit())
            .preparationInstructions(dto.getPreparationInstructions())
            .turnaroundTimeMinutes(dto.getTurnaroundTime())
            .active(dto.getActive() == null || dto.getActive())
            .assignment(assignment)
            .referenceRanges(mapToEntityRanges(dto.getReferenceRanges()))
            .build();

        entity.setHospital(null);
        return entity;
    }


    public LabTestDefinitionResponseDTO toDto(LabTestDefinition entity) {
        if (entity == null) return null;
        return LabTestDefinitionResponseDTO.builder()
                .id(entity.getId())
                .testCode(entity.getTestCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .category(entity.getCategory())
                .unit(entity.getUnit())
                .sampleType(entity.getSampleType())
                .preparationInstructions(entity.getPreparationInstructions())
                .turnaroundTime(entity.getTurnaroundTimeMinutes())
                .active(entity.isActive())
                .referenceRanges(mapToDtoRanges(entity.getReferenceRanges()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public void updateEntityFromDto(LabTestDefinitionRequestDTO dto, LabTestDefinition entity) {
        if (dto == null || entity == null) return;
        if (dto.getTestCode() != null) {
            entity.setTestCode(dto.getTestCode());
        }
        entity.setName(dto.getName());
        entity.setCategory(dto.getCategory());
        entity.setDescription(dto.getDescription());
        entity.setSampleType(dto.getSampleType());
        entity.setUnit(dto.getUnit());
        entity.setPreparationInstructions(dto.getPreparationInstructions());
        if (dto.getTurnaroundTime() != null) {
            entity.setTurnaroundTimeMinutes(dto.getTurnaroundTime());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        if (dto.getReferenceRanges() != null) {
            entity.setReferenceRanges(mapToEntityRanges(dto.getReferenceRanges()));
        }
    }

    public LabTestDefinitionResponseDTO toResponseDTO(LabTestDefinition labTestDefinition) {
        if (labTestDefinition == null) return null;

        return LabTestDefinitionResponseDTO.builder()
                .id(labTestDefinition.getId())
                .testCode(labTestDefinition.getTestCode())
                .name(labTestDefinition.getName())
                .description(labTestDefinition.getDescription())
                .category(labTestDefinition.getCategory())
                .unit(labTestDefinition.getUnit())
                .sampleType(labTestDefinition.getSampleType())
                .preparationInstructions(labTestDefinition.getPreparationInstructions())
                .turnaroundTime(labTestDefinition.getTurnaroundTimeMinutes())
                .active(labTestDefinition.isActive())
                .referenceRanges(mapToDtoRanges(labTestDefinition.getReferenceRanges()))
                .createdAt(labTestDefinition.getCreatedAt())
                .updatedAt(labTestDefinition.getUpdatedAt())
                .build();
    }

    private List<LabTestReferenceRange> mapToEntityRanges(List<LabTestReferenceRangeDTO> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return ranges.stream()
            .filter(Objects::nonNull)
            .map(range -> LabTestReferenceRange.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    private List<LabTestReferenceRangeDTO> mapToDtoRanges(List<LabTestReferenceRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return Collections.emptyList();
        }
        return ranges.stream()
            .filter(Objects::nonNull)
            .map(range -> LabTestReferenceRangeDTO.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .collect(Collectors.toCollection(java.util.ArrayList::new));
    }
}