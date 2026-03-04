package com.example.hms.controller;

import com.example.hms.payload.dto.OrganizationSecurityRuleRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityRuleResponseDTO;
import com.example.hms.service.OrganizationSecurityRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/security-rules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')")
public class OrganizationSecurityRuleController {
    private final OrganizationSecurityRuleService ruleService;

    @GetMapping
    public ResponseEntity<List<OrganizationSecurityRuleResponseDTO>> getAllRules() {
        return ResponseEntity.ok(ruleService.getAllRulesAsDto());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationSecurityRuleResponseDTO> getRuleById(@PathVariable UUID id) {
        OrganizationSecurityRuleResponseDTO dto = ruleService.getRuleByIdAsDto(id);
        return ResponseEntity.ok(dto);
    }

    @PostMapping
    public ResponseEntity<OrganizationSecurityRuleResponseDTO> createRule(
            @Valid @RequestBody OrganizationSecurityRuleRequestDTO request) {
        return ResponseEntity.ok(ruleService.createRuleFromDto(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationSecurityRuleResponseDTO> updateRule(
            @PathVariable UUID id,
            @Valid @RequestBody OrganizationSecurityRuleRequestDTO request) {
        return ResponseEntity.ok(ruleService.updateRuleFromDto(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        ruleService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }
}

