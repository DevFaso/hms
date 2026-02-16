package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import java.util.List;
import java.util.Optional;
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

    @InjectMocks
    private OrganizationSecurityPolicyServiceImpl service;

    private OrganizationSecurityPolicy policy;
    private UUID policyId;

    @BeforeEach
    void setUp() {
        policy = OrganizationSecurityPolicy.builder()
            .name("Password Rotation")
            .code("pwd-rotation")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .build();
        policyId = UUID.randomUUID();
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
}
