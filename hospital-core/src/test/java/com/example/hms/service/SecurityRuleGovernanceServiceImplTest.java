package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityRuleType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.security.SecurityRuleSet;
import com.example.hms.payload.dto.superadmin.SecurityRuleDefinitionDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportRequestDTO;
import com.example.hms.repository.SecurityRuleSetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SecurityRuleGovernanceServiceImplTest {

    @Mock
    private SecurityRuleSetRepository ruleSetRepository;

    @Mock
    private ObjectMapper objectMapper;

    private SecurityRuleGovernanceServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SecurityRuleGovernanceServiceImpl(ruleSetRepository, objectMapper);
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        lenient().when(ruleSetRepository.findByCodeIgnoreCase(any())).thenReturn(Optional.empty());
    }

    @Test
    void createRuleSetPersistsEntityAndReturnsResponse() {
        when(ruleSetRepository.save(any(SecurityRuleSet.class))).thenAnswer(invocation -> {
            SecurityRuleSet entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        SecurityRuleSetRequestDTO request = new SecurityRuleSetRequestDTO();
        request.setName("Clinical enforcement pack");
        request.setEnforcementScope("GLOBAL");
        request.setPublishedBy("Alice@example.com");
        request.setRules(List.of(buildDefinition("RBAC-SEGREGATION", 1)));

        var response = service.createRuleSet(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getCode()).contains("CLINICAL-ENFORCEMENT-PACK");
        assertThat(response.getRuleCount()).isEqualTo(1);
        assertThat(response.getPublishedBy()).isEqualTo("alice@example.com");
        ArgumentCaptor<SecurityRuleSet> captor = ArgumentCaptor.forClass(SecurityRuleSet.class);
        verify(ruleSetRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleCount()).isEqualTo(1);
        assertThat(captor.getValue().getPublishedBy()).isEqualTo("alice@example.com");
    }

    @Test
    void importTemplateCreatesRuleSetFromTemplate() {
        when(ruleSetRepository.save(any(SecurityRuleSet.class))).thenAnswer(invocation -> {
            SecurityRuleSet entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        SecurityRuleTemplateImportRequestDTO request = new SecurityRuleTemplateImportRequestDTO();
        request.setTemplateCode("DEVICE_HYGIENE");
        request.setRequestedBy("governance@example.com");

        var response = service.importTemplate(request);

        assertThat(response.getTemplateCode()).isEqualTo("DEVICE_HYGIENE");
    assertThat(response.getImportedRuleCount()).isGreaterThan(0);
    assertThat(response.getRuleSet().getCode()).isNotBlank();
    assertThat(response.getRuleSet().getName()).isEqualTo("Managed device compliance");
    assertThat(response.getImportedRules()).isNotEmpty();
    }

    @Test
    void simulatePolicyImpactCalculatesScore() {
        SecurityRuleSimulationRequestDTO request = new SecurityRuleSimulationRequestDTO();
        request.setScenario("Assess high risk change");
        request.setRules(List.of(buildDefinition("SESSION-MFA", 1), buildDefinition("NET-VPN", 2)));

        var result = service.simulatePolicyImpact(request);

        assertThat(result.getScenario()).isEqualTo("Assess high risk change");
        assertThat(result.getEvaluatedRuleCount()).isEqualTo(2);
        assertThat(result.getImpactScore()).isGreaterThan(0.0);
        assertThat(result.getImpactedControllers()).isNotEmpty();
        assertThat(result.getRecommendedActions()).isNotEmpty();
    }

    @Test
    void simulatePolicyImpactThrowsWhenNoRulesProvided() {
        SecurityRuleSimulationRequestDTO request = new SecurityRuleSimulationRequestDTO();
        request.setScenario("Invalid run");

        assertThatThrownBy(() -> service.simulatePolicyImpact(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private SecurityRuleDefinitionDTO buildDefinition(String code, int priority) {
        SecurityRuleDefinitionDTO dto = new SecurityRuleDefinitionDTO();
        dto.setName("Rule " + code);
        dto.setCode(code);
        dto.setRuleType(SecurityRuleType.ROLE_PERMISSION);
        dto.setRuleValue("{}");
        dto.setPriority(priority);
        dto.setControllers(List.of("RoleController"));
        return dto;
    }
}
