package com.example.hms.service;

import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.OrganizationSecurityRuleRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;

import java.util.List;
import java.util.UUID;

public interface OrganizationSecurityRuleService {
    List<OrganizationSecurityRule> getAllRules();
    OrganizationSecurityRule getRuleById(UUID id);
    OrganizationSecurityRule createRule(OrganizationSecurityRule rule);
    OrganizationSecurityRule updateRule(UUID id, OrganizationSecurityRule rule);
    void deleteRule(UUID id);

    // DTO-based operations (used by controller to avoid S4684 mass-assignment vulnerability)
    List<OrganizationSecurityRuleResponseDTO> getAllRulesAsDto();
    OrganizationSecurityRuleResponseDTO getRuleByIdAsDto(UUID id);
    OrganizationSecurityRuleResponseDTO createRuleFromDto(OrganizationSecurityRuleRequestDTO dto);
    OrganizationSecurityRuleResponseDTO updateRuleFromDto(UUID id, OrganizationSecurityRuleRequestDTO dto);
}
