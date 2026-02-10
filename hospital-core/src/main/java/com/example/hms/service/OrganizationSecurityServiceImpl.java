package com.example.hms.service;

import com.example.hms.config.OrganizationSecurityConstants;
import com.example.hms.enums.*;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.*;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationSecurityServiceImpl implements OrganizationSecurityService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityPolicyRepository securityPolicyRepository;
    private final OrganizationSecurityRuleRepository securityRuleRepository;

    private List<OrganizationSecurityPolicy> loadActiveSecurityPolicies(UUID organizationId) {
        List<OrganizationSecurityPolicy> policies =
            securityPolicyRepository.findByOrganizationIdAndActiveTrue(organizationId);

        policies.forEach(policy -> {
            if (policy.getRules() != null) {
                int ruleCount = policy.getRules().size();
                if (log.isTraceEnabled()) {
                    log.trace("Initialized {} security rules for policy {}", ruleCount, policy.getCode());
                }
            }
        });

        return policies;
    }

    private List<OrganizationSecurityRule> loadSecurityRulesByType(UUID organizationId, SecurityRuleType ruleType) {
        return securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, ruleType);
    }

    private OrganizationSecurityPolicy createOrUpdateSecurityPolicyInternal(Organization organization,
            String code, String name, String description, SecurityPolicyType policyType,
            Integer priority, boolean enforceStrict) {

        Optional<OrganizationSecurityPolicy> existingPolicy =
            securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), code);

        if (existingPolicy.isPresent()) {
            OrganizationSecurityPolicy policy = existingPolicy.get();
            policy.setName(name);
            policy.setDescription(description);
            policy.setPolicyType(policyType);
            policy.setPriority(priority != null ? priority : 0);
            policy.setEnforceStrict(enforceStrict);
            policy.setActive(true);

            log.info("Updated security policy: {} for organization: {}", code, organization.getCode());
            return securityPolicyRepository.save(policy);
        }

        OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
            .name(name)
            .code(code)
            .description(description)
            .policyType(policyType)
            .priority(priority != null ? priority : 0)
            .enforceStrict(enforceStrict)
            .organization(organization)
            .active(true)
            .build();

        log.info("Created security policy: {} for organization: {}", code, organization.getCode());
        return securityPolicyRepository.save(policy);
    }

    private Integer resolveSessionTimeoutMinutes(UUID organizationId) {
        List<OrganizationSecurityRule> timeoutRules =
            loadSecurityRulesByType(organizationId, SecurityRuleType.SESSION_TIMEOUT);

        return timeoutRules.stream()
            .filter(rule -> rule.getRuleValue() != null)
            .map(rule -> {
                try {
                    return Integer.parseInt(rule.getRuleValue().trim());
                } catch (NumberFormatException e) {
                    log.warn("Invalid session timeout value: {}", rule.getRuleValue());
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(Integer.parseInt(OrganizationSecurityConstants.DEFAULT_SESSION_TIMEOUT_MINUTES));
    }

    private Integer resolvePasswordMinLength(UUID organizationId) {
        List<OrganizationSecurityRule> passwordRules =
            loadSecurityRulesByType(organizationId, SecurityRuleType.PASSWORD_STRENGTH);

        return passwordRules.stream()
            .filter(rule -> rule.getRuleValue() != null)
            .map(rule -> {
                try {
                    return Integer.parseInt(rule.getRuleValue().trim());
                } catch (NumberFormatException e) {
                    log.warn("Invalid password min length value: {}", rule.getRuleValue());
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(Integer.parseInt(OrganizationSecurityConstants.DEFAULT_PASSWORD_MIN_LENGTH));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityPolicy> getActiveSecurityPolicies(UUID organizationId) {
        return loadActiveSecurityPolicies(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityRule> getActiveSecurityRules(UUID organizationId) {
        return securityRuleRepository.findActiveByOrganization(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSecurityRule> getSecurityRulesByType(UUID organizationId, SecurityRuleType ruleType) {
        return loadSecurityRulesByType(organizationId, ruleType);
    }

    @Override
    @Transactional
    public OrganizationSecurityPolicy createOrUpdateSecurityPolicy(UUID organizationId, 
            String code, String name, String description, SecurityPolicyType policyType, 
            Integer priority, boolean enforceStrict) {
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));

        return createOrUpdateSecurityPolicyInternal(organization, code, name, description,
            policyType, priority, enforceStrict);
    }

    @Override
    @Transactional
    public OrganizationSecurityRule createOrUpdateSecurityRule(UUID securityPolicyId, 
            String code, String name, String description, SecurityRuleType ruleType, 
            String ruleValue, Integer priority) {
        
        OrganizationSecurityPolicy securityPolicy = securityPolicyRepository.findById(securityPolicyId)
            .orElseThrow(() -> new ResourceNotFoundException("Security policy not found: " + securityPolicyId));

        Optional<OrganizationSecurityRule> existingRule = 
            securityRuleRepository.findBySecurityPolicyIdAndCode(securityPolicyId, code);

        if (existingRule.isPresent()) {
            // Update existing rule
            OrganizationSecurityRule rule = existingRule.get();
            rule.setName(name);
            rule.setDescription(description);
            rule.setRuleType(ruleType);
            rule.setRuleValue(ruleValue);
            rule.setPriority(priority != null ? priority : 0);
            rule.setActive(true);
            
            log.info("Updated security rule: {} for policy: {}", code, securityPolicy.getCode());
            return securityRuleRepository.save(rule);
        } else {
            // Create new rule
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name(name)
                .code(code)
                .description(description)
                .ruleType(ruleType)
                .ruleValue(ruleValue)
                .priority(priority != null ? priority : 0)
                .securityPolicy(securityPolicy)
                .active(true)
                .build();
                
            log.info("Created security rule: {} for policy: {}", code, securityPolicy.getCode());
            return securityRuleRepository.save(rule);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID organizationId, String userRole, String operation, String resource) {
        if (log.isTraceEnabled()) {
            log.trace("Evaluating permission for role {} on operation {} and resource {}", userRole, operation, resource);
        }
        List<OrganizationSecurityRule> rolePermissionRules = 
            loadSecurityRulesByType(organizationId, SecurityRuleType.ROLE_PERMISSION);

        for (OrganizationSecurityRule rule : rolePermissionRules) {
            if (evaluateRolePermissionRule(rule, userRole, operation, resource)) {
                return true;
            }
        }

        return false;
    }

    private boolean evaluateRolePermissionRule(OrganizationSecurityRule rule,
            String userRole, String operation, String resource) {
        
        if (rule.getRuleValue() == null || rule.getRuleValue().isBlank()) {
            return false;
        }

        // Parse rule value format: "ROLE_DOCTOR:READ_WRITE,ROLE_NURSE:READ,ROLE_RECEPTIONIST:READ"
        String[] roleMappings = rule.getRuleValue().split(",");
        
        for (String mapping : roleMappings) {
            String[] parts = mapping.trim().split(":");
            if (parts.length == 2) {
                String rolePattern = parts[0].trim();
                String permissions = parts[1].trim();
                String normalizedResource = resource != null ? resource.trim() : "";
                
                if (userRole.equals(rolePattern)) {
                    boolean operationAllowed = permissions.contains(operation) || permissions.contains("READ_WRITE");
                    boolean resourceAllowed = !normalizedResource.isEmpty() && permissions.contains(normalizedResource);
                    return operationAllowed || resourceAllowed;
                }
            }
        }

        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getSessionTimeoutMinutes(UUID organizationId) {
        return resolveSessionTimeoutMinutes(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getPasswordMinLength(UUID organizationId) {
        return resolvePasswordMinLength(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMfaRequired(UUID organizationId, String userRole) {
        List<OrganizationSecurityRule> mfaRules = 
            loadSecurityRulesByType(organizationId, SecurityRuleType.TWO_FACTOR_AUTH);

        for (OrganizationSecurityRule rule : mfaRules) {
            if (rule.getRuleValue() != null && rule.getRuleValue().contains(userRole)) {
                return true;
            }
        }

        // Check default sensitive roles
        return OrganizationSecurityConstants.SENSITIVE_ROLES_FOR_MFA.contains(userRole);
    }

    @Override
    @Transactional(readOnly = true)
    public String getApiRateLimit(UUID organizationId) {
        List<OrganizationSecurityRule> rateLimitRules = 
            loadSecurityRulesByType(organizationId, SecurityRuleType.API_RATE_LIMIT);

        return rateLimitRules.stream()
            .filter(rule -> rule.getRuleValue() != null && !rule.getRuleValue().isBlank())
            .map(OrganizationSecurityRule::getRuleValue)
            .findFirst()
            .orElse(OrganizationSecurityConstants.DEFAULT_API_RATE_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean shouldAuditOperation(UUID organizationId, String operation) {
        List<OrganizationSecurityRule> auditRules = 
            loadSecurityRulesByType(organizationId, SecurityRuleType.AUDIT_REQUIREMENT);

        for (OrganizationSecurityRule rule : auditRules) {
            if (rule.getRuleValue() != null && rule.getRuleValue().contains(operation)) {
                return true;
            }
        }

        return false;
    }

    @Override
    @Transactional
    public void applyDefaultSecurityPolicies(UUID organizationId, OrganizationType organizationType) {
        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));

        log.info("Applying default security policies for organization: {} of type: {}", 
            organization.getCode(), organizationType);

        // Apply policies based on organization type
        switch (organizationType) {
            case GOVERNMENT_AGENCY, RESEARCH_INSTITUTION -> applyHighSecurityPolicies(organization);
            case HEALTHCARE_NETWORK, HOSPITAL_CHAIN, ACADEMIC_MEDICAL_CENTER -> applyMediumSecurityPolicies(organization);
            case PRIVATE_PRACTICE -> applyStandardSecurityPolicies(organization);
        }
    }

    private void applyHighSecurityPolicies(Organization organization) {
        // High security policies with strict enforcement
        createOrUpdateSecurityPolicyInternal(organization,
            OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY,
            "High Security Password Policy",
            "Strict password requirements for high-security organizations",
            SecurityPolicyType.PASSWORD_POLICY, 100, true);

        createOrUpdateSecurityPolicyInternal(organization,
            OrganizationSecurityConstants.DEFAULT_SESSION_POLICY,
            "High Security Session Policy",
            "Short session timeouts for high-security organizations",
            SecurityPolicyType.SESSION_MANAGEMENT, 100, true);
    }

    private void applyMediumSecurityPolicies(Organization organization) {
        // Medium security policies with moderate enforcement
        createOrUpdateSecurityPolicyInternal(organization,
            OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY,
            "Medium Security Password Policy",
            "Standard password requirements for healthcare organizations",
            SecurityPolicyType.PASSWORD_POLICY, 80, false);
    }

    private void applyStandardSecurityPolicies(Organization organization) {
        // Standard security policies with basic enforcement
        createOrUpdateSecurityPolicyInternal(organization,
            OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY,
            "Standard Security Password Policy",
            "Basic password requirements for private practice",
            SecurityPolicyType.PASSWORD_POLICY, 60, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> validateSecurityCompliance(UUID organizationId) {
        List<String> violations = new ArrayList<>();

        // Check required policies exist
    List<OrganizationSecurityPolicy> policies = loadActiveSecurityPolicies(organizationId);
        Set<SecurityPolicyType> existingPolicyTypes = policies.stream()
            .map(OrganizationSecurityPolicy::getPolicyType)
            .collect(Collectors.toSet());

        if (!existingPolicyTypes.contains(SecurityPolicyType.ACCESS_CONTROL)) {
            violations.add("Missing required Access Control policy");
        }
        if (!existingPolicyTypes.contains(SecurityPolicyType.PASSWORD_POLICY)) {
            violations.add("Missing required Password policy");
        }
        if (!existingPolicyTypes.contains(SecurityPolicyType.AUDIT_LOGGING)) {
            violations.add("Missing required Audit Logging policy");
        }

        // Check password strength requirements
    Integer minLength = resolvePasswordMinLength(organizationId);
        if (minLength < 8) {
            violations.add("Password minimum length is below recommended 8 characters");
        }

        // Check session timeout
    Integer timeoutMinutes = resolveSessionTimeoutMinutes(organizationId);
        if (timeoutMinutes > 480) { // 8 hours
            violations.add("Session timeout exceeds recommended 8 hours");
        }

        return violations;
    }
}