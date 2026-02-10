package com.example.hms.mapper;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.*;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class OrganizationSecurityPolicyMapper {

    public OrganizationSecurityPolicyResponseDTO toResponseDTO(OrganizationSecurityPolicy policy) {
        if (policy == null) return null;

        var organizationInitialized = policy.getOrganization() != null && Hibernate.isInitialized(policy.getOrganization());

        return OrganizationSecurityPolicyResponseDTO.builder()
            .id(policy.getId())
            .name(policy.getName())
            .code(policy.getCode())
            .description(policy.getDescription())
            .policyType(policy.getPolicyType())
            .priority(policy.getPriority())
            .active(policy.isActive())
            .enforceStrict(policy.isEnforceStrict())
            .createdAt(policy.getCreatedAt())
            .updatedAt(policy.getUpdatedAt())
            .organizationId(organizationInitialized ? policy.getOrganization().getId() : null)
            .organizationName(organizationInitialized ? policy.getOrganization().getName() : null)
            .rules(mapRulesToResponseDTO(policy.getRules()))
            .build();
    }

    public OrganizationSecurityPolicy toEntity(OrganizationSecurityPolicyRequestDTO requestDTO) {
        if (requestDTO == null) return null;

        return OrganizationSecurityPolicy.builder()
            .name(requestDTO.getName())
            .code(requestDTO.getCode())
            .description(requestDTO.getDescription())
            .policyType(requestDTO.getPolicyType())
            .priority(requestDTO.getPriority())
            .active(requestDTO.isActive())
            .enforceStrict(requestDTO.isEnforceStrict())
            .build();
    }

    public void updateEntity(OrganizationSecurityPolicy policy, OrganizationSecurityPolicyRequestDTO requestDTO) {
        if (policy == null || requestDTO == null) return;

        policy.setName(requestDTO.getName());
        policy.setCode(requestDTO.getCode());
        policy.setDescription(requestDTO.getDescription());
        policy.setPolicyType(requestDTO.getPolicyType());
        policy.setPriority(requestDTO.getPriority());
        policy.setActive(requestDTO.isActive());
        policy.setEnforceStrict(requestDTO.isEnforceStrict());
    }

    private List<OrganizationSecurityRuleResponseDTO> mapRulesToResponseDTO(java.util.Set<OrganizationSecurityRule> rules) {
        if (rules == null) {
            return Collections.emptyList();
        }

        if (!Hibernate.isInitialized(rules) || rules.isEmpty()) {
            return Collections.emptyList();
        }

        return rules.stream()
            .map(this::mapRuleToResponseDTO)
            .toList();
    }

    private OrganizationSecurityRuleResponseDTO mapRuleToResponseDTO(OrganizationSecurityRule rule) {
        if (rule == null) return null;

        var policyInitialized = rule.getSecurityPolicy() != null && Hibernate.isInitialized(rule.getSecurityPolicy());

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
            .securityPolicyId(policyInitialized ? rule.getSecurityPolicy().getId() : null)
            .securityPolicyName(policyInitialized ? rule.getSecurityPolicy().getName() : null)
            .build();
    }
}