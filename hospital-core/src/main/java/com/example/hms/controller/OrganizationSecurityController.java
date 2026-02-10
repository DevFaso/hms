package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.OrganizationSecurityPolicyMapper;
import com.example.hms.mapper.OrganizationSecurityRuleMapper;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.model.OrganizationSecurityRule;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.OrganizationSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/organizations/{organizationId}/security")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Organization Security Management", description = "APIs for managing organization security policies and rules")
@SecurityRequirement(name = "Bearer Authentication")
public class OrganizationSecurityController {

    private static final String ORGANIZATION_NOT_FOUND = "Organization not found with ID: ";

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityPolicyRepository securityPolicyRepository;
    private final OrganizationSecurityService organizationSecurityService;
    private final OrganizationSecurityPolicyMapper policyMapper;
    private final OrganizationSecurityRuleMapper ruleMapper;

    @GetMapping("/compliance")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Check security compliance", 
               description = "Validate organization security compliance and get current settings")
    @ApiResponse(responseCode = "200", description = "Security compliance information retrieved")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<SecurityComplianceResponseDTO> checkSecurityCompliance(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId) {
        
        // Verify organization exists
        var organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND + organizationId));

        // Get compliance violations
        List<String> violations = organizationSecurityService.validateSecurityCompliance(organizationId);
        
        // Get current security settings
        Integer passwordMinLength = organizationSecurityService.getPasswordMinLength(organizationId);
        Integer sessionTimeout = organizationSecurityService.getSessionTimeoutMinutes(organizationId);
        String apiRateLimit = organizationSecurityService.getApiRateLimit(organizationId);
        
        // Get policy summaries
        List<OrganizationSecurityPolicy> policies = organizationSecurityService.getActiveSecurityPolicies(organizationId);
        List<SecurityComplianceResponseDTO.SecurityPolicySummary> policySummaries = policies.stream()
            .map(policy -> SecurityComplianceResponseDTO.SecurityPolicySummary.builder()
                .code(policy.getCode())
                .name(policy.getName())
                .active(policy.isActive())
                .enforceStrict(policy.isEnforceStrict())
                .rulesCount(policy.getRules() != null ? policy.getRules().size() : 0)
                .build())
            .toList();

        SecurityComplianceResponseDTO response = SecurityComplianceResponseDTO.builder()
            .organizationId(organizationId)
            .organizationName(organization.getName())
            .compliant(violations.isEmpty())
            .violations(violations)
            .passwordMinLength(passwordMinLength)
            .sessionTimeoutMinutes(sessionTimeout)
            .apiRateLimit(apiRateLimit)
            .policies(policySummaries)
            .build();

        log.info("Security compliance check for organization {}: {} violations found", 
            organization.getCode(), violations.size());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get security policies", 
               description = "Retrieve all security policies for an organization")
    @ApiResponse(responseCode = "200", description = "Security policies retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<Page<OrganizationSecurityPolicyResponseDTO>> getSecurityPolicies(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId,
            @Parameter(description = "Pagination parameters") Pageable pageable,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        
        // Verify organization exists
        organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND + organizationId));

        List<OrganizationSecurityPolicy> policies = activeOnly
            ? securityPolicyRepository.findByOrganizationIdAndActiveTrue(organizationId)
            : securityPolicyRepository.findByOrganizationId(organizationId);

        List<OrganizationSecurityPolicyResponseDTO> policyDTOs = policies.stream()
            .map(policyMapper::toResponseDTO)
            .toList();

        Page<OrganizationSecurityPolicyResponseDTO> responsePage = new PageImpl<>(policyDTOs, pageable, policyDTOs.size());
        
        log.info("Retrieved {} security policies for organization ID: {}", policyDTOs.size(), organizationId);
        return ResponseEntity.ok(responsePage);
    }

    @PostMapping("/policies")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create security policy", 
               description = "Create a new security policy for an organization")
    @ApiResponse(responseCode = "201", description = "Security policy created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<OrganizationSecurityPolicyResponseDTO> createSecurityPolicy(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId,
            @Valid @RequestBody OrganizationSecurityPolicyRequestDTO requestDTO) {
        
        // Verify organization exists
        organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND + organizationId));

        // Check if policy with this code already exists
        if (securityPolicyRepository.existsByOrganizationIdAndCode(organizationId, requestDTO.getCode())) {
            throw new IllegalArgumentException("Security policy with code '" + requestDTO.getCode() + 
                "' already exists for this organization");
        }

        OrganizationSecurityPolicy policy = organizationSecurityService.createOrUpdateSecurityPolicy(
            organizationId, requestDTO.getCode(), requestDTO.getName(), requestDTO.getDescription(),
            requestDTO.getPolicyType(), requestDTO.getPriority(), requestDTO.isEnforceStrict());

        OrganizationSecurityPolicyResponseDTO response = policyMapper.toResponseDTO(policy);
        
        log.info("Created security policy: {} for organization ID: {}", requestDTO.getCode(), organizationId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/policies/{policyId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update security policy", 
               description = "Update an existing security policy")
    @ApiResponse(responseCode = "200", description = "Security policy updated successfully")
    @ApiResponse(responseCode = "404", description = "Policy not found")
    public ResponseEntity<OrganizationSecurityPolicyResponseDTO> updateSecurityPolicy(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId,
            @Parameter(description = "Policy ID") @PathVariable UUID policyId,
            @Valid @RequestBody OrganizationSecurityPolicyRequestDTO requestDTO) {
        
        OrganizationSecurityPolicy policy = securityPolicyRepository.findById(policyId)
            .orElseThrow(() -> new ResourceNotFoundException("Security policy not found with ID: " + policyId));

        // Verify the policy belongs to the specified organization
        if (!policy.getOrganization().getId().equals(organizationId)) {
            throw new IllegalArgumentException("Policy does not belong to the specified organization");
        }

        OrganizationSecurityPolicy updatedPolicy = organizationSecurityService.createOrUpdateSecurityPolicy(
            organizationId, requestDTO.getCode(), requestDTO.getName(), requestDTO.getDescription(),
            requestDTO.getPolicyType(), requestDTO.getPriority(), requestDTO.isEnforceStrict());

        OrganizationSecurityPolicyResponseDTO response = policyMapper.toResponseDTO(updatedPolicy);
        
        log.info("Updated security policy: {} for organization ID: {}", requestDTO.getCode(), organizationId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get security rules", 
               description = "Retrieve all security rules for an organization")
    @ApiResponse(responseCode = "200", description = "Security rules retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<Page<OrganizationSecurityRuleResponseDTO>> getSecurityRules(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId,
            @Parameter(description = "Pagination parameters") Pageable pageable) {
        
        // Verify organization exists
        organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND + organizationId));

        List<OrganizationSecurityRule> rules = organizationSecurityService.getActiveSecurityRules(organizationId);
        
        List<OrganizationSecurityRuleResponseDTO> ruleDTOs = rules.stream()
            .map(ruleMapper::toResponseDTO)
            .toList();

        Page<OrganizationSecurityRuleResponseDTO> responsePage = new PageImpl<>(ruleDTOs, pageable, ruleDTOs.size());
        
        log.info("Retrieved {} security rules for organization ID: {}", ruleDTOs.size(), organizationId);
        return ResponseEntity.ok(responsePage);
    }

    @PostMapping("/policies/{policyId}/rules")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create security rule", 
               description = "Create a new security rule for a policy")
    @ApiResponse(responseCode = "201", description = "Security rule created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Policy not found")
    public ResponseEntity<OrganizationSecurityRuleResponseDTO> createSecurityRule(
            @Parameter(description = "Organization ID") @PathVariable UUID organizationId,
            @Parameter(description = "Policy ID") @PathVariable UUID policyId,
            @Valid @RequestBody OrganizationSecurityRuleRequestDTO requestDTO) {
        
        // Verify policy exists and belongs to organization
        OrganizationSecurityPolicy policy = securityPolicyRepository.findById(policyId)
            .orElseThrow(() -> new ResourceNotFoundException("Security policy not found with ID: " + policyId));

        if (!policy.getOrganization().getId().equals(organizationId)) {
            throw new IllegalArgumentException("Policy does not belong to the specified organization");
        }

        OrganizationSecurityRule rule = organizationSecurityService.createOrUpdateSecurityRule(
            policyId, requestDTO.getCode(), requestDTO.getName(), requestDTO.getDescription(),
            requestDTO.getRuleType(), requestDTO.getRuleValue(), requestDTO.getPriority());

        OrganizationSecurityRuleResponseDTO response = ruleMapper.toResponseDTO(rule);
        
        log.info("Created security rule: {} for policy ID: {}", requestDTO.getCode(), policyId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
}