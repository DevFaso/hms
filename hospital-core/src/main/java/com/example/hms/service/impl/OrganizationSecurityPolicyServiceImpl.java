package com.example.hms.service.impl;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.OrganizationSecurityPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationSecurityPolicyServiceImpl implements OrganizationSecurityPolicyService {
    private final OrganizationSecurityPolicyRepository policyRepository;

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
}
