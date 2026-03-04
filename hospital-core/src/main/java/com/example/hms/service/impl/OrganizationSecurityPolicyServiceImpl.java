package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.payload.dto.OrganizationSecurityPolicyRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityPolicyResponseDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.OrganizationSecurityPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationSecurityPolicyServiceImpl implements OrganizationSecurityPolicyService {
    private final OrganizationSecurityPolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    public List<OrganizationSecurityPolicy> getAllPolicies() {
        return policyRepository.findAll();
    }

    @Override
    public OrganizationSecurityPolicy getPolicyById(UUID id) {
        return policyRepository.findById(id).orElse(null);
    }

    @Override
    public OrganizationSecurityPolicy createPolicy(OrganizationSecurityPolicy policy) {
        return policyRepository.save(policy);
    }

    @Override
    public OrganizationSecurityPolicy updatePolicy(UUID id, OrganizationSecurityPolicy policy) {
        policy.setId(id);
        return policyRepository.save(policy);
    }

    @Override
    public void deletePolicy(UUID id) {
        policyRepository.deleteById(id);
    }

    // ---- DTO-based operations ----

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityPolicyResponseDTO> getAllPoliciesAsDto() {
        return policyRepository.findAll().stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationSecurityPolicyResponseDTO getPolicyByIdAsDto(UUID id) {
        OrganizationSecurityPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityPolicy", "id", id));
        return toResponseDto(policy);
    }

    @Override
    @Transactional
    public OrganizationSecurityPolicyResponseDTO createPolicyFromDto(OrganizationSecurityPolicyRequestDTO dto) {
        Organization org = organizationRepository.findById(dto.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", dto.getOrganizationId()));

        OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name(dto.getName())
                .code(dto.getCode())
                .description(dto.getDescription())
                .policyType(dto.getPolicyType())
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .active(dto.isActive())
                .enforceStrict(dto.isEnforceStrict())
                .organization(org)
                .build();

        return toResponseDto(policyRepository.save(policy));
    }

    @Override
    @Transactional
    public OrganizationSecurityPolicyResponseDTO updatePolicyFromDto(UUID id, OrganizationSecurityPolicyRequestDTO dto) {
        OrganizationSecurityPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OrganizationSecurityPolicy", "id", id));

        Organization org = organizationRepository.findById(dto.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", dto.getOrganizationId()));

        policy.setName(dto.getName());
        policy.setCode(dto.getCode());
        policy.setDescription(dto.getDescription());
        policy.setPolicyType(dto.getPolicyType());
        policy.setPriority(dto.getPriority() != null ? dto.getPriority() : policy.getPriority());
        policy.setActive(dto.isActive());
        policy.setEnforceStrict(dto.isEnforceStrict());
        policy.setOrganization(org);

        return toResponseDto(policyRepository.save(policy));
    }

    private OrganizationSecurityPolicyResponseDTO toResponseDto(OrganizationSecurityPolicy p) {
        List<OrganizationSecurityRuleResponseDTO> rules = p.getRules() == null ? List.of() :
                p.getRules().stream().map(r -> OrganizationSecurityRuleResponseDTO.builder()
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
                        .securityPolicyId(p.getId())
                        .securityPolicyName(p.getName())
                        .build()).toList();

        return OrganizationSecurityPolicyResponseDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .description(p.getDescription())
                .policyType(p.getPolicyType())
                .priority(p.getPriority())
                .active(p.isActive())
                .enforceStrict(p.isEnforceStrict())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .organizationId(p.getOrganization() != null ? p.getOrganization().getId() : null)
                .organizationName(p.getOrganization() != null ? p.getOrganization().getName() : null)
                .rules(rules)
                .build();
    }
}

