package com.example.hms.controller;

import com.example.hms.enums.OrganizationType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.OrganizationMapper;
import com.example.hms.mapper.OrganizationSecurityPolicyMapper;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationSecurityService;
import com.example.hms.service.platform.OrganizationPlatformBootstrapService;
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
import org.springframework.context.i18n.LocaleContextHolder;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Organization Management", description = "APIs for managing healthcare organizations")
@SecurityRequirement(name = "Bearer Authentication")
public class OrganizationController {

    private static final String ORGANIZATION_NOT_FOUND_MESSAGE = "Organization not found with ID: ";

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityService organizationSecurityService;
    private final OrganizationMapper organizationMapper;
    private final OrganizationSecurityPolicyMapper policyMapper;
    private final OrganizationPlatformBootstrapService organizationPlatformBootstrapService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get all organizations", 
               description = "Retrieve a paginated list of all organizations")
    @ApiResponse(responseCode = "200", description = "Organizations retrieved successfully")
    public ResponseEntity<Page<OrganizationResponseDTO>> getAllOrganizations(
            @Parameter(description = "Pagination parameters") Pageable pageable,
            @RequestParam(required = false) Boolean active) {
        
        Page<Organization> organizations;
        if (active != null && active) {
            List<Organization> activeOrgs = organizationRepository.findByActiveTrue();
            organizations = new PageImpl<>(activeOrgs, pageable, activeOrgs.size());
        } else {
            organizations = organizationRepository.findAll(pageable);
        }

        Page<OrganizationResponseDTO> responsePage = organizations.map(organizationMapper::toResponseDTO);
        
        log.info("Retrieved {} organizations", responsePage.getTotalElements());
        return ResponseEntity.ok(responsePage);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get organization by ID", 
               description = "Retrieve a specific organization by its ID")
    @ApiResponse(responseCode = "200", description = "Organization found")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<OrganizationResponseDTO> getOrganizationById(
            @Parameter(description = "Organization ID") @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includePolicies) {
        
        Organization organization = organizationRepository.findByIdWithHospitals(id)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND_MESSAGE + id));

        OrganizationResponseDTO response;
        if (includePolicies) {
            List<OrganizationSecurityPolicyResponseDTO> policies = organizationSecurityService
                .getActiveSecurityPolicies(id).stream()
                .map(policyMapper::toResponseDTO)
                .toList();
            response = organizationMapper.toResponseDTOWithPolicies(organization, policies);
        } else {
            response = organizationMapper.toResponseDTO(organization);
        }

        log.info("Retrieved organization: {}", organization.getCode());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create new organization", 
               description = "Create a new healthcare organization")
    @ApiResponse(responseCode = "201", description = "Organization created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    public ResponseEntity<OrganizationResponseDTO> createOrganization(
            @Valid @RequestBody OrganizationRequestDTO requestDTO) {
        
        // Check if organization code already exists
        if (organizationRepository.existsByCode(requestDTO.getCode())) {
            throw new IllegalArgumentException("Organization with code '" + requestDTO.getCode() + "' already exists");
        }

        Organization organization = organizationMapper.toEntity(requestDTO);
        organization = organizationRepository.save(organization);

        Organization organizationWithHospitals = organizationRepository
            .findByIdWithHospitals(organization.getId())
            .orElse(organization);

        // Apply default security policies based on organization type
        organizationSecurityService.applyDefaultSecurityPolicies(organization.getId(), organization.getType());
        organizationPlatformBootstrapService.bootstrapDefaultIntegrations(organization.getId(), LocaleContextHolder.getLocale());

    OrganizationResponseDTO response = organizationMapper.toResponseDTO(organizationWithHospitals);
        
        log.info("Created new organization: {}", organization.getCode());
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Update organization", 
               description = "Update an existing organization")
    @ApiResponse(responseCode = "200", description = "Organization updated successfully")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<OrganizationResponseDTO> updateOrganization(
            @Parameter(description = "Organization ID") @PathVariable UUID id,
            @Valid @RequestBody OrganizationRequestDTO requestDTO) {
        
        Organization organization = organizationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND_MESSAGE + id));

        // Check if new code conflicts with existing organization (excluding current one)
        if (!organization.getCode().equals(requestDTO.getCode()) && 
            organizationRepository.existsByCode(requestDTO.getCode())) {
            throw new IllegalArgumentException("Organization with code '" + requestDTO.getCode() + "' already exists");
        }

        organizationMapper.updateEntity(organization, requestDTO);
        organization = organizationRepository.save(organization);

        Organization organizationWithHospitals = organizationRepository
            .findByIdWithHospitals(organization.getId())
            .orElse(organization);

        OrganizationResponseDTO response = organizationMapper.toResponseDTO(organizationWithHospitals);
        
        log.info("Updated organization: {}", organization.getCode());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete organization", 
               description = "Delete an organization (soft delete by setting active=false)")
    @ApiResponse(responseCode = "204", description = "Organization deleted successfully")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<Void> deleteOrganization(
            @Parameter(description = "Organization ID") @PathVariable UUID id) {
        
        Organization organization = organizationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND_MESSAGE + id));

        organization.setActive(false);
        organizationRepository.save(organization);
        
        log.info("Soft deleted organization: {}", organization.getCode());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/types")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN')")
    @Operation(summary = "Get organization types", 
               description = "Retrieve all available organization types")
    @ApiResponse(responseCode = "200", description = "Organization types retrieved successfully")
    public ResponseEntity<List<OrganizationType>> getOrganizationTypes() {
        return ResponseEntity.ok(List.of(OrganizationType.values()));
    }

    @PostMapping("/{id}/apply-default-security")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Apply default security policies", 
               description = "Apply default security policies to an organization based on its type")
    @ApiResponse(responseCode = "200", description = "Default security policies applied successfully")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    public ResponseEntity<MessageResponse> applyDefaultSecurity(
            @Parameter(description = "Organization ID") @PathVariable UUID id) {
        
        Organization organization = organizationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(ORGANIZATION_NOT_FOUND_MESSAGE + id));

        organizationSecurityService.applyDefaultSecurityPolicies(id, organization.getType());
        
        log.info("Applied default security policies to organization: {}", organization.getCode());
        return ResponseEntity.ok(new MessageResponse("Default security policies applied successfully"));
    }
}
