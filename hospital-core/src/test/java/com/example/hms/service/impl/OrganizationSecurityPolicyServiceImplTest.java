package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.OrganizationSecurityPolicyRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityPolicyResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationSecurityPolicyServiceImplTest {

    @Mock
    private OrganizationSecurityPolicyRepository policyRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationSecurityPolicyServiceImpl service;

    private OrganizationSecurityPolicy policy;
    private UUID policyId;
    private UUID orgId;
    private Organization organization;

    @BeforeEach
    void setUp() {
        policyId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(orgId);
        organization.setName("Test Org");

        policy = OrganizationSecurityPolicy.builder()
            .name("Password Rotation")
            .code("pwd-rotation")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .organization(organization)
            .build();
        policy.setId(policyId);
    }

    @Test
    void getAllPoliciesReturnsRepositoryResult() {
        List<OrganizationSecurityPolicy> policies = List.of(policy);
        when(policyRepository.findAll()).thenReturn(policies);

        assertThat(service.getAllPolicies()).isEqualTo(policies);
        verify(policyRepository).findAll();
    }

    @Test
    void getPolicyByIdReturnsMatchWhenPresent() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        assertThat(service.getPolicyById(policyId)).isSameAs(policy);
        verify(policyRepository).findById(policyId);
    }

    @Test
    void getPolicyByIdReturnsNullWhenMissing() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThat(service.getPolicyById(policyId)).isNull();
        verify(policyRepository).findById(policyId);
    }

    @Test
    void createPolicyPersistsAndReturnsEntity() {
        when(policyRepository.save(policy)).thenReturn(policy);

        assertThat(service.createPolicy(policy)).isSameAs(policy);
        verify(policyRepository).save(policy);
    }

    @Test
    void updatePolicyAppliesIdBeforeSaving() {
        when(policyRepository.save(any(OrganizationSecurityPolicy.class)))
            .thenAnswer(invocation -> invocation.getArgument(0, OrganizationSecurityPolicy.class));

        OrganizationSecurityPolicy updated = service.updatePolicy(policyId, policy);

        assertThat(updated.getId()).isEqualTo(policyId);
        assertThat(policy.getId()).isEqualTo(policyId);
        verify(policyRepository).save(policy);
    }

    @Test
    void deletePolicyDelegatesToRepository() {
        service.deletePolicy(policyId);

        verify(policyRepository).deleteById(policyId);
    }

    // ---- DTO-based operation tests ----

    @Test
    void getAllPoliciesAsDtoReturnsMappedDtos() {
        when(policyRepository.findAll()).thenReturn(List.of(policy));

        List<OrganizationSecurityPolicyResponseDTO> result = service.getAllPoliciesAsDto();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Password Rotation");
        assertThat(result.get(0).getOrganizationId()).isEqualTo(orgId);
        assertThat(result.get(0).getOrganizationName()).isEqualTo("Test Org");
    }

    @Test
    void getPolicyByIdAsDtoReturnsDto() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        OrganizationSecurityPolicyResponseDTO result = service.getPolicyByIdAsDto(policyId);

        assertThat(result.getId()).isEqualTo(policyId);
        assertThat(result.getName()).isEqualTo("Password Rotation");
        assertThat(result.getCode()).isEqualTo("pwd-rotation");
        assertThat(result.getPolicyType()).isEqualTo(SecurityPolicyType.PASSWORD_POLICY);
    }

    @Test
    void getPolicyByIdAsDtoThrowsWhenNotFound() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicyByIdAsDto(policyId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPolicyFromDtoWithExplicitPriority() {
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("New Policy")
            .code("new-pol")
            .description("Desc")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .organizationId(orgId)
            .priority(5)
            .active(true)
            .enforceStrict(true)
            .build();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(policyRepository.save(any(OrganizationSecurityPolicy.class)))
            .thenAnswer(i -> { OrganizationSecurityPolicy p = i.getArgument(0); p.setId(UUID.randomUUID()); return p; });

        OrganizationSecurityPolicyResponseDTO result = service.createPolicyFromDto(dto);

        assertThat(result.getName()).isEqualTo("New Policy");
        assertThat(result.getPriority()).isEqualTo(5);
        assertThat(result.isEnforceStrict()).isTrue();
    }

    @Test
    void createPolicyFromDtoWithNullPriorityDefaultsToZero() {
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("Default Priority")
            .code("def-pri")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .organizationId(orgId)
            .priority(null)
            .build();

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(policyRepository.save(any(OrganizationSecurityPolicy.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityPolicyResponseDTO result = service.createPolicyFromDto(dto);

        assertThat(result.getPriority()).isEqualTo(0);
    }

    @Test
    void createPolicyFromDtoThrowsWhenOrganizationNotFound() {
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("Orphan")
            .code("orphan")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .organizationId(UUID.randomUUID())
            .build();

        when(organizationRepository.findById(dto.getOrganizationId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPolicyFromDto(dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePolicyFromDtoSuccess() {
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("Updated")
            .code("upd")
            .description("Updated desc")
            .policyType(SecurityPolicyType.ACCESS_CONTROL)
            .organizationId(orgId)
            .priority(10)
            .active(false)
            .enforceStrict(true)
            .build();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(policyRepository.save(any(OrganizationSecurityPolicy.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityPolicyResponseDTO result = service.updatePolicyFromDto(policyId, dto);

        assertThat(result.getName()).isEqualTo("Updated");
        assertThat(result.getPriority()).isEqualTo(10);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void updatePolicyFromDtoWithNullPriorityKeepsExisting() {
        policy.setPriority(7);
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("Keep Priority")
            .code("keep")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .organizationId(orgId)
            .priority(null)
            .build();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(policyRepository.save(any(OrganizationSecurityPolicy.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityPolicyResponseDTO result = service.updatePolicyFromDto(policyId, dto);

        assertThat(result.getPriority()).isEqualTo(7);
    }

    @Test
    void updatePolicyFromDtoThrowsWhenPolicyNotFound() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("X").code("x").policyType(SecurityPolicyType.PASSWORD_POLICY).organizationId(orgId).build();

        assertThatThrownBy(() -> service.updatePolicyFromDto(policyId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePolicyFromDtoThrowsWhenOrganizationNotFound() {
        UUID badOrgId = UUID.randomUUID();
        OrganizationSecurityPolicyRequestDTO dto = OrganizationSecurityPolicyRequestDTO.builder()
            .name("X").code("x").policyType(SecurityPolicyType.PASSWORD_POLICY).organizationId(badOrgId).build();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(organizationRepository.findById(badOrgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePolicyFromDto(policyId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void toResponseDtoMapsRulesWhenPresent() {
        OrganizationSecurityRule rule = OrganizationSecurityRule.builder()
            .name("Rule1")
            .code("r1")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .ruleValue("100")
            .priority(1)
            .active(true)
            .build();
        rule.setId(UUID.randomUUID());
        policy.setRules(new HashSet<>(Set.of(rule)));

        when(policyRepository.findAll()).thenReturn(List.of(policy));

        List<OrganizationSecurityPolicyResponseDTO> result = service.getAllPoliciesAsDto();

        assertThat(result.get(0).getRules()).hasSize(1);
        assertThat(result.get(0).getRules().get(0).getName()).isEqualTo("Rule1");
        assertThat(result.get(0).getRules().get(0).getSecurityPolicyId()).isEqualTo(policyId);
        assertThat(result.get(0).getRules().get(0).getSecurityPolicyName()).isEqualTo("Password Rotation");
    }

    @Test
    void toResponseDtoHandlesNullRules() {
        policy.setRules(null);

        when(policyRepository.findAll()).thenReturn(List.of(policy));

        List<OrganizationSecurityPolicyResponseDTO> result = service.getAllPoliciesAsDto();

        assertThat(result.get(0).getRules()).isEmpty();
    }

    @Test
    void toResponseDtoHandlesNullOrganization() {
        policy.setOrganization(null);

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        OrganizationSecurityPolicyResponseDTO result = service.getPolicyByIdAsDto(policyId);

        assertThat(result.getOrganizationId()).isNull();
        assertThat(result.getOrganizationName()).isNull();
    }
}
