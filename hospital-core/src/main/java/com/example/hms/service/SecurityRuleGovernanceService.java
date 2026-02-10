package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.SecurityRuleSetRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetResponseDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationResultDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportResponseDTO;
import java.util.List;

public interface SecurityRuleGovernanceService {

    SecurityRuleSetResponseDTO createRuleSet(SecurityRuleSetRequestDTO request);

    List<SecurityRuleTemplateDTO> listTemplates();

    SecurityRuleTemplateImportResponseDTO importTemplate(SecurityRuleTemplateImportRequestDTO request);

    SecurityRuleSimulationResultDTO simulatePolicyImpact(SecurityRuleSimulationRequestDTO request);
}
