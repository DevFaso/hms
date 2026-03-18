package com.example.hms.mapper;

import com.example.hms.model.LabReflexRule;
import com.example.hms.payload.dto.LabReflexRuleResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class LabReflexRuleMapper {

    public LabReflexRuleResponseDTO toResponseDTO(LabReflexRule rule) {
        if (rule == null) return null;
        return LabReflexRuleResponseDTO.builder()
            .id(rule.getId())
            .triggerTestDefinitionId(rule.getTriggerTestDefinition() != null
                ? rule.getTriggerTestDefinition().getId() : null)
            .triggerTestDefinitionName(rule.getTriggerTestDefinition() != null
                ? rule.getTriggerTestDefinition().getName() : null)
            .condition(rule.getCondition())
            .reflexTestDefinitionId(rule.getReflexTestDefinition() != null
                ? rule.getReflexTestDefinition().getId() : null)
            .reflexTestDefinitionName(rule.getReflexTestDefinition() != null
                ? rule.getReflexTestDefinition().getName() : null)
            .active(rule.isActive())
            .description(rule.getDescription())
            .createdAt(rule.getCreatedAt())
            .updatedAt(rule.getUpdatedAt())
            .build();
    }
}
