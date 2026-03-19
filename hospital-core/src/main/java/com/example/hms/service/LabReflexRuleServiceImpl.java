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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabReflexRuleServiceImpl implements LabReflexRuleService {

    private final LabReflexRuleRepository reflexRuleRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabReflexRuleMapper reflexRuleMapper;

    @Override
    @Transactional
    public LabReflexRuleResponseDTO createRule(LabReflexRuleRequestDTO request, Locale locale) {
        validateRequest(request);
        LabTestDefinition triggerDef = loadTestDef(request.getTriggerTestDefinitionId());
        LabTestDefinition reflexDef  = loadTestDef(request.getReflexTestDefinitionId());

        LabReflexRule rule = LabReflexRule.builder()
            .triggerTestDefinition(triggerDef)
            .condition(request.getCondition().trim())
            .reflexTestDefinition(reflexDef)
            .active(request.isActive())
            .description(request.getDescription())
            .build();

        return reflexRuleMapper.toResponseDTO(reflexRuleRepository.save(rule));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabReflexRuleResponseDTO> getAllRules(Locale locale) {
        return reflexRuleRepository.findAll().stream()
            .map(reflexRuleMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public LabReflexRuleResponseDTO updateRule(UUID id, LabReflexRuleRequestDTO request, Locale locale) {
        LabReflexRule rule = reflexRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("reflexrule.notfound"));

        if (request.getCondition() != null && !request.getCondition().isBlank()) {
            rule.setCondition(request.getCondition().trim());
        }
        rule.setActive(request.isActive());
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        if (request.getTriggerTestDefinitionId() != null) {
            rule.setTriggerTestDefinition(loadTestDef(request.getTriggerTestDefinitionId()));
        }
        if (request.getReflexTestDefinitionId() != null) {
            rule.setReflexTestDefinition(loadTestDef(request.getReflexTestDefinitionId()));
        }
        return reflexRuleMapper.toResponseDTO(reflexRuleRepository.save(rule));
    }

    private void validateRequest(LabReflexRuleRequestDTO request) {
        if (request.getTriggerTestDefinitionId() == null) {
            throw new BusinessException("triggerTestDefinitionId is required.");
        }
        if (request.getReflexTestDefinitionId() == null) {
            throw new BusinessException("reflexTestDefinitionId is required.");
        }
        if (request.getCondition() == null || request.getCondition().isBlank()) {
            throw new BusinessException("condition JSON is required.");
        }
    }

    private LabTestDefinition loadTestDef(UUID id) {
        return labTestDefinitionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("labtestdef.notfound"));
    }
}
