package com.example.hms.service;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.payload.dto.OrganizationSecurityPolicyRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityPolicyResponseDTO;

import java.util.List;
import java.util.UUID;

public interface OrganizationSecurityPolicyService {
    List<OrganizationSecurityPolicy> getAllPolicies();
    OrganizationSecurityPolicy getPolicyById(UUID id);
    OrganizationSecurityPolicy createPolicy(OrganizationSecurityPolicy policy);
    OrganizationSecurityPolicy updatePolicy(UUID id, OrganizationSecurityPolicy policy);
    void deletePolicy(UUID id);

    // DTO-based operations (used by controller to avoid S4684 mass-assignment vulnerability)
    List<OrganizationSecurityPolicyResponseDTO> getAllPoliciesAsDto();
    OrganizationSecurityPolicyResponseDTO getPolicyByIdAsDto(UUID id);
    OrganizationSecurityPolicyResponseDTO createPolicyFromDto(OrganizationSecurityPolicyRequestDTO dto);
    OrganizationSecurityPolicyResponseDTO updatePolicyFromDto(UUID id, OrganizationSecurityPolicyRequestDTO dto);
}
