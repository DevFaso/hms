package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityRuleType;
import com.example.hms.model.OrganizationSecurityRule;
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

    @InjectMocks
    private OrganizationSecurityRuleServiceImpl service;

    private OrganizationSecurityRule rule;
    private UUID ruleId;

    @BeforeEach
    void setUp() {
        rule = OrganizationSecurityRule.builder()
            .name("Block Legacy API")
            .code("block-legacy-api")
            .ruleType(SecurityRuleType.API_RATE_LIMIT)
            .build();
        ruleId = UUID.randomUUID();
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
}
