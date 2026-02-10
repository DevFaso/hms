package com.example.hms.mapper;

import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.OrganizationSecurityRuleRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class OrganizationSecurityRuleMapper {

    public OrganizationSecurityRuleResponseDTO toResponseDTO(OrganizationSecurityRule rule) {
        if (rule == null) return null;

        return OrganizationSecurityRuleResponseDTO.builder()
            .id(rule.getId())
            .name(rule.getName())
            .code(rule.getCode())
            .description(rule.getDescription())
            .ruleType(rule.getRuleType())
            .ruleValue(rule.getRuleValue())
            .priority(rule.getPriority())
            .active(rule.isActive())
            .createdAt(rule.getCreatedAt())
            .updatedAt(rule.getUpdatedAt())
            .securityPolicyId(rule.getSecurityPolicy() != null ? rule.getSecurityPolicy().getId() : null)
            .securityPolicyName(rule.getSecurityPolicy() != null ? rule.getSecurityPolicy().getName() : null)
            .build();
    }

    public OrganizationSecurityRule toEntity(OrganizationSecurityRuleRequestDTO requestDTO) {
        if (requestDTO == null) return null;

        return OrganizationSecurityRule.builder()
            .name(requestDTO.getName())
            .code(requestDTO.getCode())
            .description(requestDTO.getDescription())
            .ruleType(requestDTO.getRuleType())
            .ruleValue(requestDTO.getRuleValue())
            .priority(requestDTO.getPriority())
            .active(requestDTO.isActive())
            .build();
    }

    public void updateEntity(OrganizationSecurityRule rule, OrganizationSecurityRuleRequestDTO requestDTO) {
        if (rule == null || requestDTO == null) return;

        rule.setName(requestDTO.getName());
        rule.setCode(requestDTO.getCode());
        rule.setDescription(requestDTO.getDescription());
        rule.setRuleType(requestDTO.getRuleType());
        rule.setRuleValue(requestDTO.getRuleValue());
        rule.setPriority(requestDTO.getPriority());
        rule.setActive(requestDTO.isActive());
    }
}