package com.example.hms.controller;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO;
import com.example.hms.service.SuperAdminOrganizationOverviewService;
import com.example.hms.service.SuperAdminOrganizationProvisioningService;
import com.example.hms.service.HospitalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;

@RestController
@RequestMapping("/super-admin/organizations")
@RequiredArgsConstructor
@Tag(name = "Super Admin Organizations", description = "Tenant management and compliance overview")
public class SuperAdminOrganizationController {

    private final SuperAdminOrganizationOverviewService overviewService;
    private final SuperAdminOrganizationProvisioningService provisioningService;
    private final HospitalService hospitalService;

    @GetMapping("/summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Organization overview for super admins", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SuperAdminOrganizationsSummaryDTO> getOrganizationsSummary() {
        return ResponseEntity.ok(overviewService.getOrganizationsSummary());
    }

    @GetMapping("/hierarchy")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Structured organization hierarchy",
        description = "Returns organizations with nested hospitals, staff, and patient summaries for super admin exploration.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<SuperAdminOrganizationHierarchyResponseDTO> getOrganizationHierarchy(
        @RequestParam(name = "includeStaff", defaultValue = "true") boolean includeStaff,
        @RequestParam(name = "includePatients", defaultValue = "false") boolean includePatients,
        @RequestParam(name = "activeOnly", required = false) Boolean activeOnly,
        @RequestParam(name = "search", required = false) String search,
        @RequestParam(name = "staffLimit", defaultValue = "25") int staffLimit,
        @RequestParam(name = "patientLimit", defaultValue = "15") int patientLimit
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        SuperAdminOrganizationHierarchyResponseDTO response = overviewService.getOrganizationHierarchy(
            includeStaff,
            includePatients,
            activeOnly,
            search,
            staffLimit,
            patientLimit,
            locale
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Provision a new organization",
        description = "Creates a new organization with default security posture and onboarding metadata",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<SuperAdminCreateOrganizationResponseDTO> createOrganization(
        @Valid @RequestBody SuperAdminCreateOrganizationRequestDTO request
    ) {
        OrganizationResponseDTO created = provisioningService.createOrganization(request);
        SuperAdminCreateOrganizationResponseDTO response = SuperAdminCreateOrganizationResponseDTO.builder()
            .id(created.getId())
            .code(created.getCode())
            .name(created.getName())
            .message("Organization created successfully.")
            .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{organizationId}/hospitals/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Assign hospital to organization",
        description = "Links an existing hospital to an organization in the super-admin context",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<HospitalResponseDTO> assignHospital(
        @PathVariable UUID organizationId,
        @PathVariable UUID hospitalId
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        HospitalResponseDTO current = hospitalService.getHospitalById(hospitalId, locale);
        if (current.getOrganizationId() != null && !current.getOrganizationId().equals(organizationId)) {
            throw new BusinessRuleException("Hospital is already assigned to a different organization");
        }

        HospitalResponseDTO updated = hospitalService.assignHospitalToOrganization(hospitalId, organizationId, locale);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{organizationId}/hospitals/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        summary = "Unassign hospital from organization",
        description = "Removes the organization association from a hospital",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<HospitalResponseDTO> unassignHospital(
        @PathVariable UUID organizationId,
        @PathVariable UUID hospitalId
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        HospitalResponseDTO current = hospitalService.getHospitalById(hospitalId, locale);
        if (current.getOrganizationId() == null || !organizationId.equals(current.getOrganizationId())) {
            throw new BusinessRuleException("Hospital is not assigned to the specified organization");
        }

        HospitalResponseDTO updated = hospitalService.unassignHospitalFromOrganization(hospitalId, locale);
        return ResponseEntity.ok(updated);
    }
}
