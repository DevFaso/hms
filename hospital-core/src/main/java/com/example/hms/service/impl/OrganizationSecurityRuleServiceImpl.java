package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.OrganizationSecurityRuleRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
import com.example.hms.service.OrganizationSecurityRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationSecurityRuleServiceImpl implements OrganizationSecurityRuleService {
    private final OrganizationSecurityRuleRepository ruleRepository;
    private final OrganizationSecurityPolicyRepository policyRepository;

    @Override
    public List<OrganizationSecurityRule> getAllRules() {
        return ruleRepository.findAll();
    }

    @Override
    public OrganizationSecurityRule getRuleById(UUID id) {
        return ruleRepository.findById(id).orElse(null);
    }

    @Override
    public OrganizationSecurityRule createRule(OrganizationSecurityRule rule) {
        return ruleRepository.save(rule);
    }

    @Override
    public OrganizationSecurityRule updateRule(UUID id, OrganizationSecurityRule rule) {
        rule.setId(id);
        return ruleRepository.save(rule);
    }

    @Override
    public void deleteRule(UUID id) {
        ruleRepository.deleteById(id);
    }

    // ---- DTO-based operations ----

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityRuleResponseDTO> getAllRulesAsDto() {
        return ruleRepository.findAll().stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationSecurityRuleResponseDTO getRuleByIdAsDto(UUID id) {
        OrganizationSecurityRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityRule", "id", id));
        return toResponseDto(rule);
    }

    @Override
    @Transactional
    public OrganizationSecurityRuleResponseDTO createRuleFromDto(OrganizationSecurityRuleRequestDTO dto) {
        OrganizationSecurityPolicy policy = policyRepository.findById(dto.getSecurityPolicyId())
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityPolicy", "id", dto.getSecurityPolicyId()));

        OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name(dto.getName())
                .code(dto.getCode())
                .description(dto.getDescription())
                .ruleType(dto.getRuleType())
                .ruleValue(dto.getRuleValue())
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .active(dto.isActive())
                .securityPolicy(policy)
                .build();

        return toResponseDto(ruleRepository.save(rule));
    }

    @Override
    @Transactional
    public OrganizationSecurityRuleResponseDTO updateRuleFromDto(UUID id, OrganizationSecurityRuleRequestDTO dto) {
        OrganizationSecurityRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityRule", "id", id));

        OrganizationSecurityPolicy policy = policyRepository.findById(dto.getSecurityPolicyId())
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityPolicy", "id", dto.getSecurityPolicyId()));

        rule.setName(dto.getName());
        rule.setCode(dto.getCode());
        rule.setDescription(dto.getDescription());
        rule.setRuleType(dto.getRuleType());
        rule.setRuleValue(dto.getRuleValue());
        rule.setPriority(dto.getPriority() != null ? dto.getPriority() : rule.getPriority());
        rule.setActive(dto.isActive());
        rule.setSecurityPolicy(policy);

        return toResponseDto(ruleRepository.save(rule));
    }

    private OrganizationSecurityRuleResponseDTO toResponseDto(OrganizationSecurityRule r) {
        OrganizationSecurityPolicy p = r.getSecurityPolicy();
        return OrganizationSecurityRuleResponseDTO.builder()
                .id(r.getId())
                .name(r.getName())
                .code(r.getCode())
                .description(r.getDescription())
                .ruleType(r.getRuleType())
                .ruleValue(r.getRuleValue())
                .priority(r.getPriority())
                .active(r.isActive())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .securityPolicyId(p != null ? p.getId() : null)
                .securityPolicyName(p != null ? p.getName() : null)
                .build();
    }
}

