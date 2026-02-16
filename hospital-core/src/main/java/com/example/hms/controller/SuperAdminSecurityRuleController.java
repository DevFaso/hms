package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.SecurityRuleSetRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetResponseDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationResultDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportResponseDTO;
import com.example.hms.service.SecurityRuleGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/super-admin/security/rules")
@RequiredArgsConstructor
@Tag(name = "Super Admin Security Rules", description = "Govern security rule sets, templates and simulations")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminSecurityRuleController {

    private final SecurityRuleGovernanceService securityRuleGovernanceService;

    @PostMapping("/rule-sets")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a new security rule set")
    public ResponseEntity<SecurityRuleSetResponseDTO> createRuleSet(
        @Valid @RequestBody SecurityRuleSetRequestDTO request
    ) {
        SecurityRuleSetResponseDTO response = securityRuleGovernanceService.createRuleSet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/templates")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List available security rule templates")
    public ResponseEntity<List<SecurityRuleTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(securityRuleGovernanceService.listTemplates());
    }

    @PostMapping("/templates/import")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Import a security rule template into a rule set")
    public ResponseEntity<SecurityRuleTemplateImportResponseDTO> importTemplate(
        @Valid @RequestBody SecurityRuleTemplateImportRequestDTO request
    ) {
        SecurityRuleTemplateImportResponseDTO response = securityRuleGovernanceService.importTemplate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/simulations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Simulate the impact of security rules")
    public ResponseEntity<SecurityRuleSimulationResultDTO> simulatePolicyImpact(
        @Valid @RequestBody SecurityRuleSimulationRequestDTO request
    ) {
        SecurityRuleSimulationResultDTO response = securityRuleGovernanceService.simulatePolicyImpact(request);
        return ResponseEntity.ok(response);
    }
}
