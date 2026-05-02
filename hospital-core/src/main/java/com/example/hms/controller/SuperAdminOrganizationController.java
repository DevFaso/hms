package com.example.hms.controller;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleResponseDTO;
import com.example.hms.service.OrganizationLifecycleService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final OrganizationLifecycleService lifecycleService;

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

    // ── Tenant lifecycle (MVP-2 — gap #2 in docs/super-admin-gaps.md) ──

    @GetMapping("/{organizationId}/lifecycle")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get tenant-lifecycle snapshot", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> getLifecycle(@PathVariable UUID organizationId) {
        return ResponseEntity.ok(lifecycleService.getLifecycle(organizationId));
    }

    @PostMapping("/{organizationId}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Suspend an organization (block all logins org-wide)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> suspend(
        @PathVariable UUID organizationId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.suspend(organizationId, request, mfaToken));
    }

    @PostMapping("/{organizationId}/restore")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Restore a suspended or archived organization",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> restore(
        @PathVariable UUID organizationId,
        @Valid @RequestBody(required = false) TenantLifecycleActionRequestDTO request
    ) {
        return ResponseEntity.ok(lifecycleService.restore(organizationId, request));
    }

    @PostMapping("/{organizationId}/archive")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Archive an organization (soft delete; data retained, hidden by default)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> archive(
        @PathVariable UUID organizationId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.archive(organizationId, request, mfaToken));
    }

    @PostMapping("/{organizationId}/schedule-purge")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Schedule purge for an archived organization (default 30-day grace)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> schedulePurge(
        @PathVariable UUID organizationId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.schedulePurge(organizationId, request, mfaToken));
    }

    @PostMapping("/{organizationId}/cancel-purge")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cancel a scheduled purge", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TenantLifecycleResponseDTO> cancelPurge(
        @PathVariable UUID organizationId,
        @Valid @RequestBody(required = false) TenantLifecycleActionRequestDTO request
    ) {
        return ResponseEntity.ok(lifecycleService.cancelPurge(organizationId, request));
    }
}
