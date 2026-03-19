package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabReflexRuleMapper;
import com.example.hms.model.LabReflexRule;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabReflexRuleRequestDTO;
import com.example.hms.payload.dto.LabReflexRuleResponseDTO;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabReflexRuleServiceImplTest {

    @Mock private LabReflexRuleRepository reflexRuleRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private LabReflexRuleMapper reflexRuleMapper;

    @InjectMocks
    private LabReflexRuleServiceImpl service;

    private UUID triggerTestDefId;
    private UUID reflexTestDefId;
    private UUID ruleId;
    private LabTestDefinition triggerDef;
    private LabTestDefinition reflexDef;
    private LabReflexRuleResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        triggerTestDefId = UUID.randomUUID();
        reflexTestDefId  = UUID.randomUUID();
        ruleId           = UUID.randomUUID();
        triggerDef = new LabTestDefinition();
        reflexDef  = new LabTestDefinition();
        responseDTO = LabReflexRuleResponseDTO.builder().id(ruleId).build();
    }

    // ── createRule ────────────────────────────────────────────────────────────

    @Test
    void createRule_success() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .reflexTestDefinitionId(reflexTestDefId)
            .condition("{\"value\":\">\",\"threshold\":10}")
            .description("Auto reflex for high values")
            .active(true)
            .build();

        LabReflexRule saved = new LabReflexRule();
        when(labTestDefinitionRepository.findById(triggerTestDefId)).thenReturn(Optional.of(triggerDef));
        when(labTestDefinitionRepository.findById(reflexTestDefId)).thenReturn(Optional.of(reflexDef));
        when(reflexRuleRepository.save(any())).thenReturn(saved);
        when(reflexRuleMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabReflexRuleResponseDTO result = service.createRule(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        ArgumentCaptor<LabReflexRule> captor = ArgumentCaptor.forClass(LabReflexRule.class);
        verify(reflexRuleRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerTestDefinition()).isEqualTo(triggerDef);
        assertThat(captor.getValue().getReflexTestDefinition()).isEqualTo(reflexDef);
        assertThat(captor.getValue().getCondition()).isEqualTo("{\"value\":\">\",\"threshold\":10}");
    }

    @Test
    void createRule_missingTriggerTestId_throwsBusinessException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .reflexTestDefinitionId(reflexTestDefId)
            .condition("{\"threshold\":5}")
            .build();

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("triggerTestDefinitionId");
    }

    @Test
    void createRule_missingReflexTestId_throwsBusinessException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .condition("{\"threshold\":5}")
            .build();

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reflexTestDefinitionId");
    }

    @Test
    void createRule_blankCondition_throwsBusinessException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .reflexTestDefinitionId(reflexTestDefId)
            .condition("   ")
            .build();

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("condition");
    }

    @Test
    void createRule_nullCondition_throwsBusinessException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .reflexTestDefinitionId(reflexTestDefId)
            .build(); // condition is null

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("condition");
    }

    @Test
    void createRule_triggerTestNotFound_throwsResourceNotFoundException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .reflexTestDefinitionId(reflexTestDefId)
            .condition("{\"threshold\":5}")
            .build();

        when(labTestDefinitionRepository.findById(triggerTestDefId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createRule_reflexTestNotFound_throwsResourceNotFoundException() {
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .triggerTestDefinitionId(triggerTestDefId)
            .reflexTestDefinitionId(reflexTestDefId)
            .condition("{\"threshold\":5}")
            .build();

        when(labTestDefinitionRepository.findById(triggerTestDefId)).thenReturn(Optional.of(triggerDef));
        when(labTestDefinitionRepository.findById(reflexTestDefId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRule(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getAllRules ────────────────────────────────────────────────────────────

    @Test
    void getAllRules_success_returnsMappedList() {
        LabReflexRule rule = new LabReflexRule();
        when(reflexRuleRepository.findAll()).thenReturn(List.of(rule));
        when(reflexRuleMapper.toResponseDTO(rule)).thenReturn(responseDTO);

        List<LabReflexRuleResponseDTO> result = service.getAllRules(Locale.ENGLISH);

        assertThat(result).containsExactly(responseDTO);
    }

    @Test
    void getAllRules_empty_returnsEmptyList() {
        when(reflexRuleRepository.findAll()).thenReturn(List.of());

        List<LabReflexRuleResponseDTO> result = service.getAllRules(Locale.ENGLISH);

        assertThat(result).isEmpty();
    }

    // ── updateRule ────────────────────────────────────────────────────────────

    @Test
    void updateRule_success_updatesAllFields() {
        LabReflexRule existingRule = LabReflexRule.builder()
            .triggerTestDefinition(triggerDef)
            .reflexTestDefinition(reflexDef)
            .condition("{\"old\":\"condition\"}")
            .active(false)
            .description("old description")
            .build();

        UUID newTriggerDefId = UUID.randomUUID();
        UUID newReflexDefId  = UUID.randomUUID();
        LabTestDefinition newTriggerDef = new LabTestDefinition();
        LabTestDefinition newReflexDef  = new LabTestDefinition();

        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .condition("{\"new\":\"condition\"}")
            .active(true)
            .description("updated description")
            .triggerTestDefinitionId(newTriggerDefId)
            .reflexTestDefinitionId(newReflexDefId)
            .build();

        when(reflexRuleRepository.findById(ruleId)).thenReturn(Optional.of(existingRule));
        when(labTestDefinitionRepository.findById(newTriggerDefId)).thenReturn(Optional.of(newTriggerDef));
        when(labTestDefinitionRepository.findById(newReflexDefId)).thenReturn(Optional.of(newReflexDef));
        LabReflexRule saved = new LabReflexRule();
        when(reflexRuleRepository.save(existingRule)).thenReturn(saved);
        when(reflexRuleMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabReflexRuleResponseDTO result = service.updateRule(ruleId, request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        assertThat(existingRule.getCondition()).isEqualTo("{\"new\":\"condition\"}");
        assertThat(existingRule.isActive()).isTrue();
        assertThat(existingRule.getDescription()).isEqualTo("updated description");
        assertThat(existingRule.getTriggerTestDefinition()).isEqualTo(newTriggerDef);
        assertThat(existingRule.getReflexTestDefinition()).isEqualTo(newReflexDef);
    }

    @Test
    void updateRule_notFound_throwsResourceNotFoundException() {
        when(reflexRuleRepository.findById(ruleId)).thenReturn(Optional.empty());

        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder().build();

        assertThatThrownBy(() -> service.updateRule(ruleId, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRule_preservesExistingTestDefsWhenNullInRequest() {
        LabReflexRule existingRule = LabReflexRule.builder()
            .triggerTestDefinition(triggerDef)
            .reflexTestDefinition(reflexDef)
            .condition("{\"threshold\":5}")
            .build();

        // request has no new test def IDs → existing ones should be preserved
        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .condition("{\"threshold\":10}")
            .build();

        LabReflexRule saved = new LabReflexRule();
        when(reflexRuleRepository.findById(ruleId)).thenReturn(Optional.of(existingRule));
        when(reflexRuleRepository.save(existingRule)).thenReturn(saved);
        when(reflexRuleMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        service.updateRule(ruleId, request, Locale.ENGLISH);

        assertThat(existingRule.getTriggerTestDefinition()).isEqualTo(triggerDef);
        assertThat(existingRule.getReflexTestDefinition()).isEqualTo(reflexDef);
    }

    @Test
    void updateRule_blankCondition_doesNotOverwriteExistingCondition() {
        LabReflexRule existingRule = LabReflexRule.builder()
            .triggerTestDefinition(triggerDef)
            .reflexTestDefinition(reflexDef)
            .condition("{\"original\":\"condition\"}")
            .build();

        LabReflexRuleRequestDTO request = LabReflexRuleRequestDTO.builder()
            .condition("  ") // blank → should not overwrite
            .build();

        LabReflexRule saved = new LabReflexRule();
        when(reflexRuleRepository.findById(ruleId)).thenReturn(Optional.of(existingRule));
        when(reflexRuleRepository.save(existingRule)).thenReturn(saved);
        when(reflexRuleMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        service.updateRule(ruleId, request, Locale.ENGLISH);

        assertThat(existingRule.getCondition()).isEqualTo("{\"original\":\"condition\"}");
    }
}
