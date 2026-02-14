package com.example.hms.service;

import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationSecurityServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationSecurityPolicyRepository securityPolicyRepository;

    @Mock
    private OrganizationSecurityRuleRepository securityRuleRepository;

    @InjectMocks
    private OrganizationSecurityServiceImpl organizationSecurityService;

    private UUID organizationId;
    private Organization organization;
    private OrganizationSecurityPolicy policy;
    private OrganizationSecurityRule rule;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        
        organization = Organization.builder()
            .name("Test Hospital Network")
            .code("TEST_ORG")
            .type(OrganizationType.HEALTHCARE_NETWORK)
            .active(true)
            .build();
        organization.setId(organizationId);

        policy = OrganizationSecurityPolicy.builder()
            .name("Test Access Control Policy")
            .code("TEST_ACCESS_CONTROL")
            .policyType(SecurityPolicyType.ACCESS_CONTROL)
            .organization(organization)
            .active(true)
            .priority(100)
            .build();
        policy.setId(UUID.randomUUID());

        rule = OrganizationSecurityRule.builder()
            .name("Test Session Timeout Rule")
            .code("TEST_SESSION_TIMEOUT")
            .ruleType(SecurityRuleType.SESSION_TIMEOUT)
            .ruleValue("480")
            .securityPolicy(policy)
            .active(true)
            .priority(10)
            .build();
        rule.setId(UUID.randomUUID());
    }

    @Test
    void testGetActiveSecurityPolicies() {
        // Arrange
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(organizationId))
            .thenReturn(List.of(policy));

        // Act
        List<OrganizationSecurityPolicy> result = organizationSecurityService.getActiveSecurityPolicies(organizationId);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(policy.getCode(), result.get(0).getCode());
        verify(securityPolicyRepository).findByOrganizationIdAndActiveTrue(organizationId);
    }

    @Test
    void testGetSessionTimeoutMinutes() {
        // Arrange
        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.SESSION_TIMEOUT))
            .thenReturn(List.of(rule));

        // Act
        Integer result = organizationSecurityService.getSessionTimeoutMinutes(organizationId);

        // Assert
        assertNotNull(result);
        assertEquals(480, result);
        verify(securityRuleRepository).findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.SESSION_TIMEOUT);
    }

    @Test
    void testGetSessionTimeoutMinutes_DefaultValue() {
        // Arrange
        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.SESSION_TIMEOUT))
            .thenReturn(Collections.emptyList());

        // Act
        Integer result = organizationSecurityService.getSessionTimeoutMinutes(organizationId);

        // Assert
        assertNotNull(result);
        assertEquals(480, result); // Default value from OrganizationSecurityConstants
    }

    @Test
    void testGetPasswordMinLength() {
        // Arrange
        OrganizationSecurityRule passwordRule = OrganizationSecurityRule.builder()
            .ruleType(SecurityRuleType.PASSWORD_STRENGTH)
            .ruleValue("12")
            .active(true)
            .build();

        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.PASSWORD_STRENGTH))
            .thenReturn(List.of(passwordRule));

        // Act
        Integer result = organizationSecurityService.getPasswordMinLength(organizationId);

        // Assert
        assertNotNull(result);
        assertEquals(12, result);
    }

    @Test
    void testHasPermission() {
        // Arrange
        OrganizationSecurityRule permissionRule = OrganizationSecurityRule.builder()
            .ruleType(SecurityRuleType.ROLE_PERMISSION)
            .ruleValue("ROLE_DOCTOR:READ_WRITE,ROLE_NURSE:READ")
            .active(true)
            .build();

        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.ROLE_PERMISSION))
            .thenReturn(List.of(permissionRule));

        // Act & Assert
        assertTrue(organizationSecurityService.hasPermission(organizationId, "ROLE_DOCTOR", "READ", "PATIENT"));
        assertTrue(organizationSecurityService.hasPermission(organizationId, "ROLE_DOCTOR", "WRITE", "PATIENT"));
        assertTrue(organizationSecurityService.hasPermission(organizationId, "ROLE_NURSE", "READ", "PATIENT"));
        assertFalse(organizationSecurityService.hasPermission(organizationId, "ROLE_NURSE", "WRITE", "PATIENT"));
        assertFalse(organizationSecurityService.hasPermission(organizationId, "ROLE_RECEPTIONIST", "READ", "PATIENT"));
    }

    @Test
    void testIsMfaRequired() {
        // Arrange
        OrganizationSecurityRule mfaRule = OrganizationSecurityRule.builder()
            .ruleType(SecurityRuleType.TWO_FACTOR_AUTH)
            .ruleValue("ROLE_DOCTOR,ROLE_NURSE")
            .active(true)
            .build();

        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.TWO_FACTOR_AUTH))
            .thenReturn(List.of(mfaRule));

        // Act & Assert
        assertTrue(organizationSecurityService.isMfaRequired(organizationId, "ROLE_DOCTOR"));
        assertTrue(organizationSecurityService.isMfaRequired(organizationId, "ROLE_NURSE"));
        assertFalse(organizationSecurityService.isMfaRequired(organizationId, "ROLE_RECEPTIONIST"));
    }

    @Test
    void testShouldAuditOperation() {
        // Arrange
        OrganizationSecurityRule auditRule = OrganizationSecurityRule.builder()
            .ruleType(SecurityRuleType.AUDIT_REQUIREMENT)
            .ruleValue("PATIENT_CREATE,PATIENT_UPDATE,PRESCRIPTION_CREATE")
            .active(true)
            .build();

        when(securityRuleRepository.findActiveByOrganizationAndRuleType(organizationId, SecurityRuleType.AUDIT_REQUIREMENT))
            .thenReturn(List.of(auditRule));

        // Act & Assert
        assertTrue(organizationSecurityService.shouldAuditOperation(organizationId, "PATIENT_CREATE"));
        assertTrue(organizationSecurityService.shouldAuditOperation(organizationId, "PRESCRIPTION_CREATE"));
        assertFalse(organizationSecurityService.shouldAuditOperation(organizationId, "PATIENT_READ"));
    }

    @Test
    void testCreateOrUpdateSecurityPolicy() {
        // Arrange
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(securityPolicyRepository.findByOrganizationIdAndCode(organizationId, "NEW_POLICY"))
            .thenReturn(Optional.empty());
        when(securityPolicyRepository.save(any(OrganizationSecurityPolicy.class))).thenReturn(policy);

        // Act
        OrganizationSecurityPolicy result = organizationSecurityService.createOrUpdateSecurityPolicy(
            organizationId, "NEW_POLICY", "New Policy", "Test policy", 
            SecurityPolicyType.ACCESS_CONTROL, 90, false);

        // Assert
        assertNotNull(result);
        verify(organizationRepository).findById(organizationId);
        verify(securityPolicyRepository).findByOrganizationIdAndCode(organizationId, "NEW_POLICY");
        verify(securityPolicyRepository).save(any(OrganizationSecurityPolicy.class));
    }

    @Test
    void testValidateSecurityCompliance() {
        // Arrange
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(organizationId))
            .thenReturn(Collections.emptyList()); // No policies = violations

        when(securityRuleRepository.findActiveByOrganizationAndRuleType(eq(organizationId), any(SecurityRuleType.class)))
            .thenReturn(Collections.emptyList());

        // Act
        List<String> violations = organizationSecurityService.validateSecurityCompliance(organizationId);

        // Assert
        assertNotNull(violations);
        assertFalse(violations.isEmpty());
        assertTrue(violations.contains("Missing required Access Control policy"));
        assertTrue(violations.contains("Missing required Password policy"));
        assertTrue(violations.contains("Missing required Audit Logging policy"));
    }
}