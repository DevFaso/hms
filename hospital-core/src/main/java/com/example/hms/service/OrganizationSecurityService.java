package com.example.hms.service;

import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for managing organization security policies and rules
 */
public interface OrganizationSecurityService {

    /**
     * Get all active security policies for an organization
     */
    List<OrganizationSecurityPolicy> getActiveSecurityPolicies(UUID organizationId);

    /**
     * Get all active security rules for an organization
     */
    List<OrganizationSecurityRule> getActiveSecurityRules(UUID organizationId);

    /**
     * Get security rules by type for an organization
     */
    List<OrganizationSecurityRule> getSecurityRulesByType(UUID organizationId, SecurityRuleType ruleType);

    /**
     * Create or update a security policy for an organization
     */
    OrganizationSecurityPolicy createOrUpdateSecurityPolicy(UUID organizationId, 
        String code, String name, String description, SecurityPolicyType policyType, 
        Integer priority, boolean enforceStrict);

    /**
     * Create or update a security rule for a policy
     */
    OrganizationSecurityRule createOrUpdateSecurityRule(UUID securityPolicyId, 
        String code, String name, String description, SecurityRuleType ruleType, 
        String ruleValue, Integer priority);

    /**
     * Check if a user has permission based on organization security rules
     */
    boolean hasPermission(UUID organizationId, String userRole, String operation, String resource);

    /**
     * Get session timeout for an organization
     */
    Integer getSessionTimeoutMinutes(UUID organizationId);

    /**
     * Get password minimum length for an organization
     */
    Integer getPasswordMinLength(UUID organizationId);

    /**
     * Check if multi-factor authentication is required for a role in an organization
     */
    boolean isMfaRequired(UUID organizationId, String userRole);

    /**
     * Get API rate limit for an organization
     */
    String getApiRateLimit(UUID organizationId);

    /**
     * Check if an operation should be audited for an organization
     */
    boolean shouldAuditOperation(UUID organizationId, String operation);

    /**
     * Apply default security policies to an organization
     */
    void applyDefaultSecurityPolicies(UUID organizationId, OrganizationType organizationType);

    /**
     * Validate organization security compliance
     */
    List<String> validateSecurityCompliance(UUID organizationId);
}