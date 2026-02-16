package com.example.hms.service;

import com.example.hms.model.OrganizationSecurityPolicy;
import java.util.List;
import java.util.UUID;

public interface OrganizationSecurityPolicyService {
    List<OrganizationSecurityPolicy> getAllPolicies();
    OrganizationSecurityPolicy getPolicyById(UUID id);
    OrganizationSecurityPolicy createPolicy(OrganizationSecurityPolicy policy);
    OrganizationSecurityPolicy updatePolicy(UUID id, OrganizationSecurityPolicy policy);
    void deletePolicy(UUID id);
}
