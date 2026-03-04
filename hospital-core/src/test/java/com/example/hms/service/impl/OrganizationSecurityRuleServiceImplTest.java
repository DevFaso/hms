package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityPolicyType;
import com.example.hms.enums.SecurityRuleType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.OrganizationSecurityRuleRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.repository.OrganizationSecurityRuleRepository;
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
class OrganizationSecurityRuleServiceImplTest {

    @Mock
    private OrganizationSecurityRuleRepository ruleRepository;

    @Mock
    private OrganizationSecurityPolicyRepository policyRepository;

    @InjectMocks
    private OrganizationSecurityRuleServiceImpl service;

    private OrganizationSecurityRule rule;
    private OrganizationSecurityPolicy parentPolicy;
    private UUID ruleId;
    private UUID policyId;

    @BeforeEach
    void setUp() {
        ruleId = UUID.randomUUID();
        policyId = UUID.randomUUID();

        parentPolicy = OrganizationSecurityPolicy.builder()
            .name("Parent Policy")
            .code("parent")
            .policyType(SecurityPolicyType.PASSWORD_POLICY)
            .build();
        parentPolicy.setId(policyId);

        rule = OrganizationSecurityRule.builder()
            .name("Block Legacy API")
            .code("block-legacy-api")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .ruleValue("100")
            .securityPolicy(parentPolicy)
            .build();
        rule.setId(ruleId);
    }

    @Test
    void getAllRulesReturnsRepositoryResult() {
        List<OrganizationSecurityRule> rules = List.of(rule);
        when(ruleRepository.findAll()).thenReturn(rules);

        assertThat(service.getAllRules()).isEqualTo(rules);
        verify(ruleRepository).findAll();
    }

    @Test
    void getRuleByIdReturnsMatchWhenPresent() {
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        assertThat(service.getRuleById(ruleId)).isSameAs(rule);
        verify(ruleRepository).findById(ruleId);
    }

    @Test
    void getRuleByIdReturnsNullWhenMissing() {
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.empty());

        assertThat(service.getRuleById(ruleId)).isNull();
        verify(ruleRepository).findById(ruleId);
    }

    @Test
    void createRulePersistsAndReturnsEntity() {
        when(ruleRepository.save(rule)).thenReturn(rule);

        assertThat(service.createRule(rule)).isSameAs(rule);
        verify(ruleRepository).save(rule);
    }

    @Test
    void updateRuleAppliesIdBeforeSaving() {
        when(ruleRepository.save(any(OrganizationSecurityRule.class)))
            .thenAnswer(invocation -> invocation.getArgument(0, OrganizationSecurityRule.class));

        OrganizationSecurityRule updated = service.updateRule(ruleId, rule);

        assertThat(updated.getId()).isEqualTo(ruleId);
        assertThat(rule.getId()).isEqualTo(ruleId);
        verify(ruleRepository).save(rule);
    }

    @Test
    void deleteRuleDelegatesToRepository() {
        service.deleteRule(ruleId);

        verify(ruleRepository).deleteById(ruleId);
    }

    // ---- DTO-based operation tests ----

    @Test
    void getAllRulesAsDtoReturnsMappedDtos() {
        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        List<OrganizationSecurityRuleResponseDTO> result = service.getAllRulesAsDto();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Block Legacy API");
        assertThat(result.get(0).getSecurityPolicyId()).isEqualTo(policyId);
        assertThat(result.get(0).getSecurityPolicyName()).isEqualTo("Parent Policy");
    }

    @Test
    void getRuleByIdAsDtoReturnsDto() {
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));

        OrganizationSecurityRuleResponseDTO result = service.getRuleByIdAsDto(ruleId);

        assertThat(result.getId()).isEqualTo(ruleId);
        assertThat(result.getName()).isEqualTo("Block Legacy API");
        assertThat(result.getCode()).isEqualTo("block-legacy-api");
        assertThat(result.getRuleType()).isEqualTo(SecurityRuleType.API_RATE_LIMIT);
        assertThat(result.getRuleValue()).isEqualTo("100");
    }

    @Test
    void getRuleByIdAsDtoThrowsWhenNotFound() {
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRuleByIdAsDto(ruleId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createRuleFromDtoWithExplicitPriority() {
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("New Rule")
            .code("new-r")
            .description("Desc")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .ruleValue("200")
            .securityPolicyId(policyId)
            .priority(5)
            .active(true)
            .build();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(parentPolicy));
        when(ruleRepository.save(any(OrganizationSecurityRule.class)))
            .thenAnswer(i -> { OrganizationSecurityRule r = i.getArgument(0); r.setId(UUID.randomUUID()); return r; });

        OrganizationSecurityRuleResponseDTO result = service.createRuleFromDto(dto);

        assertThat(result.getName()).isEqualTo("New Rule");
        assertThat(result.getPriority()).isEqualTo(5);
        assertThat(result.getRuleValue()).isEqualTo("200");
    }

    @Test
    void createRuleFromDtoWithNullPriorityDefaultsToZero() {
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("Default Priority Rule")
            .code("def-pri-r")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .securityPolicyId(policyId)
            .priority(null)
            .build();

        when(policyRepository.findById(policyId)).thenReturn(Optional.of(parentPolicy));
        when(ruleRepository.save(any(OrganizationSecurityRule.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityRuleResponseDTO result = service.createRuleFromDto(dto);

        assertThat(result.getPriority()).isZero();
    }

    @Test
    void createRuleFromDtoThrowsWhenPolicyNotFound() {
        UUID badPolicyId = UUID.randomUUID();
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("Orphan Rule")
            .code("orphan-r")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .securityPolicyId(badPolicyId)
            .build();

        when(policyRepository.findById(badPolicyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRuleFromDto(dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRuleFromDtoSuccess() {
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("Updated Rule")
            .code("upd-r")
            .description("Updated desc")
            .ruleType(SecurityRuleType.IP_WHITELIST)
            .ruleValue("192.168.1.0/24")
            .securityPolicyId(policyId)
            .priority(10)
            .active(false)
            .build();

        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(parentPolicy));
        when(ruleRepository.save(any(OrganizationSecurityRule.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityRuleResponseDTO result = service.updateRuleFromDto(ruleId, dto);

        assertThat(result.getName()).isEqualTo("Updated Rule");
        assertThat(result.getPriority()).isEqualTo(10);
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void updateRuleFromDtoWithNullPriorityKeepsExisting() {
        rule.setPriority(7);
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("Keep Priority")
            .code("keep")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .securityPolicyId(policyId)
            .priority(null)
            .build();

        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(parentPolicy));
        when(ruleRepository.save(any(OrganizationSecurityRule.class)))
            .thenAnswer(i -> i.getArgument(0));

        OrganizationSecurityRuleResponseDTO result = service.updateRuleFromDto(ruleId, dto);

        assertThat(result.getPriority()).isEqualTo(7);
    }

    @Test
    void updateRuleFromDtoThrowsWhenRuleNotFound() {
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.empty());

        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("X").code("x").ruleType(SecurityRuleType.API_RATE_LIMIT).securityPolicyId(policyId).build();

        assertThatThrownBy(() -> service.updateRuleFromDto(ruleId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRuleFromDtoThrowsWhenPolicyNotFound() {
        UUID badPolicyId = UUID.randomUUID();
        OrganizationSecurityRuleRequestDTO dto = OrganizationSecurityRuleRequestDTO.builder()
            .name("X").code("x").ruleType(SecurityRuleType.API_RATE_LIMIT).securityPolicyId(badPolicyId).build();

        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(rule));
        when(policyRepository.findById(badPolicyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRuleFromDto(ruleId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void toResponseDtoHandlesNullSecurityPolicy() {
        rule.setSecurityPolicy(null);

        when(ruleRepository.findAll()).thenReturn(List.of(rule));

        List<OrganizationSecurityRuleResponseDTO> result = service.getAllRulesAsDto();

        assertThat(result.get(0).getSecurityPolicyId()).isNull();
        assertThat(result.get(0).getSecurityPolicyName()).isNull();
    }
}
