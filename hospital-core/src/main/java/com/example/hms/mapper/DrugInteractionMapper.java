package com.example.hms.mapper;

import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;
import org.springframework.stereotype.Component;

/**
 * Mapper for DrugInteraction entity and DTOs.
 */
@Component
public class DrugInteractionMapper {

    /**
     * Convert entity to DTO.
     */
    public DrugInteractionDTO toDTO(DrugInteraction entity) {
        if (entity == null) {
            return null;
        }

        return DrugInteractionDTO.builder()
            .id(entity.getId())
            .drug1Code(entity.getDrug1Code())
            .drug1Name(entity.getDrug1Name())
            .drug2Code(entity.getDrug2Code())
            .drug2Name(entity.getDrug2Name())
            .severity(entity.getSeverity())
            .description(entity.getDescription())
            .recommendation(entity.getRecommendation())
            .mechanism(entity.getMechanism())
            .clinicalEffects(entity.getClinicalEffects())
            .requiresAvoidance(entity.isRequiresAvoidance())
            .requiresDoseAdjustment(entity.isRequiresDoseAdjustment())
            .requiresMonitoring(entity.isRequiresMonitoring())
            .monitoringParameters(entity.getMonitoringParameters())
            .monitoringIntervalHours(entity.getMonitoringIntervalHours())
            .sourceDatabase(entity.getSourceDatabase())
            .evidenceLevel(entity.getEvidenceLevel())
            .literatureReferences(entity.getLiteratureReferences())
            .active(entity.isActive())
            .build();
    }
}
