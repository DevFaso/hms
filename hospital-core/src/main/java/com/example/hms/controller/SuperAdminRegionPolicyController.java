package com.example.hms.controller;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.payload.dto.superadmin.RegionPolicyCapabilitiesDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyResponseDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyUpdateRequestDTO;
import com.example.hms.service.RegionPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Super-admin endpoints for the per-region policy table (MVP-c
 * batch — MVP-9c).
 */
@RestController
@RequestMapping("/super-admin/data-residency/policies")
@RequiredArgsConstructor
@Tag(name = "Super Admin — Region Policy",
    description = "Per-region retention / export-format / deployment-routing overrides.")
public class SuperAdminRegionPolicyController {

    private final RegionPolicyService regionPolicyService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List every region's policy snapshot",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<RegionPolicyResponseDTO>> listAll() {
        return ResponseEntity.ok(regionPolicyService.listAll());
    }

    @GetMapping("/capabilities")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Capability flags driving editor field gating",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RegionPolicyCapabilitiesDTO> capabilities() {
        return ResponseEntity.ok(RegionPolicyCapabilitiesDTO.builder()
            .remoteProvisioningCapable(regionPolicyService.isRemoteProvisioningCapable())
            .build());
    }

    @GetMapping("/{region}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get one region's policy snapshot",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RegionPolicyResponseDTO> get(@PathVariable OrganizationRegion region) {
        return ResponseEntity.ok(regionPolicyService.get(region));
    }

    @PutMapping("/{region}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update one region's policy overrides",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<RegionPolicyResponseDTO> update(
        @PathVariable OrganizationRegion region,
        @Valid @RequestBody(required = false) RegionPolicyUpdateRequestDTO request
    ) {
        return ResponseEntity.ok(regionPolicyService.update(region, request));
    }
}
