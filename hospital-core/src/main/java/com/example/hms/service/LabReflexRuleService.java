package com.example.hms.service;

import com.example.hms.payload.dto.LabReflexRuleRequestDTO;
import com.example.hms.payload.dto.LabReflexRuleResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface LabReflexRuleService {

    /** Create a new reflex / add-on test rule. */
    LabReflexRuleResponseDTO createRule(LabReflexRuleRequestDTO request, Locale locale);

    /** List all rules (active + inactive). */
    List<LabReflexRuleResponseDTO> getAllRules(Locale locale);

    /** Update the condition, active flag, or description of an existing rule. */
    LabReflexRuleResponseDTO updateRule(UUID id, LabReflexRuleRequestDTO request, Locale locale);
}
