package com.example.hms.seed;

import com.example.hms.config.OrganizationSecurityConstants;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;
import com.example.hms.model.*;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(2) // Run after RoleSeeder
public class OrganizationSecuritySeeder implements CommandLineRunner {

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityPolicyRepository securityPolicyRepository;
    private final OrganizationSecurityRuleRepository securityRuleRepository;
    private final HospitalRepository hospitalRepository;

    @Override
    public void run(String... args) {
        seedDefaultOrganization();
        seedDefaultSecurityPolicies();
        linkHospitalsToDefaultOrganization();
    }

    private void seedDefaultOrganization() {
        organizationRepository.findByCode("DEFAULT_ORG").orElseGet(() -> {
            Organization defaultOrg = Organization.builder()
                .name("Default Healthcare Organization")
                .code("DEFAULT_ORG")
                .description("Default organization for hospitals without specific organization assignment")
                .type(OrganizationType.HEALTHCARE_NETWORK)
                .active(true)
                .build();
            
            log.info("Seeding default organization: {}", defaultOrg.getCode());
            return organizationRepository.save(defaultOrg);
        });
    }

    private void seedDefaultSecurityPolicies() {
        Organization defaultOrg = organizationRepository.findByCode("DEFAULT_ORG").orElse(null);
        if (defaultOrg == null) {
            log.error("Default organization not found - cannot seed security policies");
            return;
        }

        // Seed Access Control Policy
        seedAccessControlPolicy(defaultOrg);
        
        // Seed Data Protection Policy
        seedDataProtectionPolicy(defaultOrg);
        
        // Seed Audit Logging Policy
        seedAuditLoggingPolicy(defaultOrg);
        
        // Seed Password Policy
        seedPasswordPolicy(defaultOrg);
        
        // Seed Session Management Policy
        seedSessionManagementPolicy(defaultOrg);
        
        // Seed Role Management Policy
        seedRoleManagementPolicy(defaultOrg);
    }

    private void seedAccessControlPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_ACCESS_CONTROL_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Access Control Policy")
                .code(OrganizationSecurityConstants.DEFAULT_ACCESS_CONTROL_POLICY)
                .description("Default access control rules for healthcare organizations")
                .policyType(SecurityPolicyType.ACCESS_CONTROL)
                .organization(organization)
                .priority(100)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded access control policy for organization: {}", organization.getCode());
            
            // Add default access control rules
            seedAccessControlRules(savedPolicy);
            
            return savedPolicy;
        });
    }

    private void seedAccessControlRules(OrganizationSecurityPolicy policy) {
        // Patient Data Access Rule
        securityRuleRepository.findBySecurityPolicyIdAndCode(policy.getId(),
            OrganizationSecurityConstants.PATIENT_DATA_ACCESS_RULE).orElseGet(() -> {
            
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name("Patient Data Access Rule")
                .code(OrganizationSecurityConstants.PATIENT_DATA_ACCESS_RULE)
                .description("Controls access to patient data based on role")
                .ruleType(SecurityRuleType.ROLE_PERMISSION)
                .ruleValue("ROLE_DOCTOR:READ_WRITE,ROLE_NURSE:READ_WRITE,ROLE_RECEPTIONIST:READ,ROLE_LAB_SCIENTIST:READ")
                .securityPolicy(policy)
                .priority(10)
                .active(true)
                .build();
                
            log.info("Seeded patient data access rule");
            return securityRuleRepository.save(rule);
        });

        // Admin Endpoint Access Rule
        securityRuleRepository.findBySecurityPolicyIdAndCode(policy.getId(),
            OrganizationSecurityConstants.ADMIN_ENDPOINT_ACCESS_RULE).orElseGet(() -> {
            
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name("Admin Endpoint Access Rule")
                .code(OrganizationSecurityConstants.ADMIN_ENDPOINT_ACCESS_RULE)
                .description("Controls access to administrative endpoints")
                .ruleType(SecurityRuleType.ENDPOINT_ACCESS)
                .ruleValue("/api/admin/**:ROLE_SUPER_ADMIN,ROLE_HOSPITAL_ADMIN")
                .securityPolicy(policy)
                .priority(20)
                .active(true)
                .build();
                
            log.info("Seeded admin endpoint access rule");
            return securityRuleRepository.save(rule);
        });
    }

    private void seedDataProtectionPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_DATA_PROTECTION_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Data Protection Policy")
                .code(OrganizationSecurityConstants.DEFAULT_DATA_PROTECTION_POLICY)
                .description("Default data protection and privacy rules")
                .policyType(SecurityPolicyType.DATA_PROTECTION)
                .organization(organization)
                .priority(90)
                .enforceStrict(true)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded data protection policy for organization: {}", organization.getCode());
            
            return savedPolicy;
        });
    }

    private void seedAuditLoggingPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_AUDIT_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Audit Logging Policy")
                .code(OrganizationSecurityConstants.DEFAULT_AUDIT_POLICY)
                .description("Default audit logging requirements")
                .policyType(SecurityPolicyType.AUDIT_LOGGING)
                .organization(organization)
                .priority(80)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded audit logging policy for organization: {}", organization.getCode());
            
            // Add audit rules
            seedAuditRule(savedPolicy);
            
            return savedPolicy;
        });
    }

    private void seedAuditRule(OrganizationSecurityPolicy policy) {
        securityRuleRepository.findBySecurityPolicyIdAndCode(policy.getId(),
            OrganizationSecurityConstants.AUDIT_SENSITIVE_OPERATIONS_RULE).orElseGet(() -> {
            
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name("Audit Sensitive Operations")
                .code(OrganizationSecurityConstants.AUDIT_SENSITIVE_OPERATIONS_RULE)
                .description("Audit all sensitive medical operations")
                .ruleType(SecurityRuleType.AUDIT_REQUIREMENT)
                .ruleValue("PATIENT_CREATE,PATIENT_UPDATE,PRESCRIPTION_CREATE,LAB_RESULT_UPDATE")
                .securityPolicy(policy)
                .priority(10)
                .active(true)
                .build();
                
            log.info("Seeded audit sensitive operations rule");
            return securityRuleRepository.save(rule);
        });
    }

    private void seedPasswordPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Password Policy")
                .code(OrganizationSecurityConstants.DEFAULT_PASSWORD_POLICY)
                .description("Default password strength requirements")
                .policyType(SecurityPolicyType.PASSWORD_POLICY)
                .organization(organization)
                .priority(70)
                .enforceStrict(true)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded password policy for organization: {}", organization.getCode());
            
            // Add password strength rule
            seedPasswordRule(savedPolicy);
            
            return savedPolicy;
        });
    }

    private void seedPasswordRule(OrganizationSecurityPolicy policy) {
        securityRuleRepository.findBySecurityPolicyIdAndCode(policy.getId(),
            OrganizationSecurityConstants.PASSWORD_MIN_LENGTH_RULE).orElseGet(() -> {
            
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name("Password Minimum Length")
                .code(OrganizationSecurityConstants.PASSWORD_MIN_LENGTH_RULE)
                .description("Minimum password length requirement")
                .ruleType(SecurityRuleType.PASSWORD_STRENGTH)
                .ruleValue(OrganizationSecurityConstants.DEFAULT_PASSWORD_MIN_LENGTH)
                .securityPolicy(policy)
                .priority(10)
                .active(true)
                .build();
                
            log.info("Seeded password minimum length rule");
            return securityRuleRepository.save(rule);
        });
    }

    private void seedSessionManagementPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_SESSION_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Session Management Policy")
                .code(OrganizationSecurityConstants.DEFAULT_SESSION_POLICY)
                .description("Default session management and timeout rules")
                .policyType(SecurityPolicyType.SESSION_MANAGEMENT)
                .organization(organization)
                .priority(60)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded session management policy for organization: {}", organization.getCode());
            
            // Add session timeout rule
            seedSessionTimeoutRule(savedPolicy);
            
            return savedPolicy;
        });
    }

    private void seedSessionTimeoutRule(OrganizationSecurityPolicy policy) {
        securityRuleRepository.findBySecurityPolicyIdAndCode(policy.getId(),
            OrganizationSecurityConstants.SESSION_TIMEOUT_RULE).orElseGet(() -> {
            
            OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
                .name("Session Timeout")
                .code(OrganizationSecurityConstants.SESSION_TIMEOUT_RULE)
                .description("Session timeout in minutes")
                .ruleType(SecurityRuleType.SESSION_TIMEOUT)
                .ruleValue(OrganizationSecurityConstants.DEFAULT_SESSION_TIMEOUT_MINUTES)
                .securityPolicy(policy)
                .priority(10)
                .active(true)
                .build();
                
            log.info("Seeded session timeout rule");
            return securityRuleRepository.save(rule);
        });
    }

    private void seedRoleManagementPolicy(Organization organization) {
        securityPolicyRepository.findByOrganizationIdAndCode(organization.getId(), 
            OrganizationSecurityConstants.DEFAULT_ROLE_POLICY).orElseGet(() -> {
            
            OrganizationSecurityPolicy policy = OrganizationSecurityPolicy.builder()
                .name("Default Role Management Policy")
                .code(OrganizationSecurityConstants.DEFAULT_ROLE_POLICY)
                .description("Default role assignment and management rules")
                .policyType(SecurityPolicyType.ROLE_MANAGEMENT)
                .organization(organization)
                .priority(50)
                .active(true)
                .build();
                
            OrganizationSecurityPolicy savedPolicy = securityPolicyRepository.save(policy);
            log.info("Seeded role management policy for organization: {}", organization.getCode());
            
            return savedPolicy;
        });
    }

    private void linkHospitalsToDefaultOrganization() {
        Organization defaultOrg = organizationRepository.findByCode("DEFAULT_ORG").orElse(null);
        if (defaultOrg == null) {
            log.error("Default organization not found - cannot link hospitals");
            return;
        }

        List<Hospital> hospitalsWithoutOrg = hospitalRepository.findByOrganizationIsNull();
        if (!hospitalsWithoutOrg.isEmpty()) {
            log.info("Linking {} hospitals to default organization", hospitalsWithoutOrg.size());
            
            for (Hospital hospital : hospitalsWithoutOrg) {
                hospital.setOrganization(defaultOrg);
                hospitalRepository.save(hospital);
                log.debug("Linked hospital {} to default organization", hospital.getCode());
            }
        }
    }
}