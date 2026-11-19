package com.example.hms.mapper;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.payload.dto.LabTestComponentDTO;
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

        // The hospital is set by the service from the acting scope (D30/D73).
        return LabTestDefinition.builder()
            .testCode(dto.getTestCode())
            .name(dto.getName())
            .category(dto.getCategory())
            .description(dto.getDescription())
            .sampleType(dto.getSampleType())
            .unit(dto.getUnit())
            .loincCode(dto.getLoincCode())
            .loincDisplay(dto.getLoincDisplay())
            .preparationInstructions(dto.getPreparationInstructions())
            .expectedResult(dto.getExpectedResult())
            .turnaroundTimeMinutes(dto.getTurnaroundTime())
            // QA D535: a new definition is a DRAFT, and a DRAFT is not active;
            // only the approval workflow's ACTIVATE step switches it on.
            .active(false)
            .assignment(assignment)
            .referenceRanges(mapRangeDtosToEntities(dto.getReferenceRanges()))
            .components(mapComponentDtosToEntities(dto.getComponents()))
            .build();
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
                .loincCode(entity.getLoincCode())
                .loincDisplay(entity.getLoincDisplay())
                .sampleType(entity.getSampleType())
                .preparationInstructions(entity.getPreparationInstructions())
                .expectedResult(entity.getExpectedResult())
                .turnaroundTime(entity.getTurnaroundTimeMinutes())
                // QA D535: "active" means orderable - a DRAFT (or any status
                // short of ACTIVE) is never reported active, even for rows
                // created before new definitions started inactive.
                .active(entity.isOrderable())
                .approvalStatus(entity.getApprovalStatus() != null ? entity.getApprovalStatus().name() : null)
                .approvedById(entity.getApprovedById())
                .approvedAt(entity.getApprovedAt())
                .submittedById(entity.getSubmittedById())
                .reviewedById(entity.getReviewedById())
                .reviewedAt(entity.getReviewedAt())
                .rejectionReason(entity.getRejectionReason())
                .supersedesDefinitionId(entity.getSupersedesDefinitionId())
                .versionNumber(entity.getVersionNumber() != null ? entity.getVersionNumber() : 1)
                .referenceRanges(mapToDtoRanges(entity.getReferenceRanges()))
                .components(mapToDtoComponents(entity.getComponents()))
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
        entity.setLoincCode(dto.getLoincCode());
        entity.setLoincDisplay(dto.getLoincDisplay());
        entity.setPreparationInstructions(dto.getPreparationInstructions());
        entity.setExpectedResult(dto.getExpectedResult());
        if (dto.getTurnaroundTime() != null) {
            entity.setTurnaroundTimeMinutes(dto.getTurnaroundTime());
        }
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        if (dto.getReferenceRanges() != null) {
            entity.setReferenceRanges(mapRangeDtosToEntities(dto.getReferenceRanges()));
        }
        if (dto.getComponents() != null) {
            entity.setComponents(mapComponentDtosToEntities(dto.getComponents()));
        }
    }

    public LabTestDefinitionResponseDTO toResponseDTO(LabTestDefinition labTestDefinition) {
        return toDto(labTestDefinition);
    }

    public List<LabTestReferenceRange> mapRangeDtosToEntities(List<LabTestReferenceRangeDTO> ranges) {
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
                .criticalLow(range.getCriticalLow())
                .criticalHigh(range.getCriticalHigh())
                .ageUnit(range.getAgeUnit())
                .source(range.getSource())
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
                .criticalLow(range.getCriticalLow())
                .criticalHigh(range.getCriticalHigh())
                .ageUnit(range.getAgeUnit())
                .source(range.getSource())
                .build())
            .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    /** r63 (NFS panels): codes are trimmed and upper-cased so a result names them unambiguously. */
    public List<LabTestComponent> mapComponentDtosToEntities(List<LabTestComponentDTO> components) {
        if (components == null || components.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return components.stream()
            .filter(Objects::nonNull)
            .map(component -> LabTestComponent.builder()
                .code(component.getCode() == null ? null
                    : component.getCode().trim().toUpperCase(java.util.Locale.ROOT))
                .name(component.getName() == null ? null : component.getName().trim())
                .unit(component.getUnit() == null || component.getUnit().isBlank() ? null : component.getUnit().trim())
                .referenceRanges(mapRangeDtosToEntities(component.getReferenceRanges()))
                .build())
            .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    private List<LabTestComponentDTO> mapToDtoComponents(List<LabTestComponent> components) {
        if (components == null || components.isEmpty()) {
            return Collections.emptyList();
        }
        return components.stream()
            .filter(Objects::nonNull)
            .map(component -> LabTestComponentDTO.builder()
                .code(component.getCode())
                .name(component.getName())
                .unit(component.getUnit())
                .referenceRanges(mapToDtoRanges(component.getReferenceRanges()))
                .build())
            .collect(Collectors.toCollection(java.util.ArrayList::new));
    }
}
