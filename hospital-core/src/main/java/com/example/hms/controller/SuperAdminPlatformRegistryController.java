package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;
import com.example.hms.service.SuperAdminPlatformRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/super-admin/platform")
@RequiredArgsConstructor
@Tag(name = "Super Admin Platform Registry", description = "Manage cross-tenant platform integrations and release automation")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminPlatformRegistryController {

    private final SuperAdminPlatformRegistryService registryService;

    @GetMapping("/registry/summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Platform registry overview for super admins")
    public ResponseEntity<SuperAdminPlatformRegistrySummaryDTO> getRegistrySummary() {
        return ResponseEntity.ok(registryService.getRegistrySummary());
    }

    @PostMapping("/release-windows")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Schedule a platform release window")
    public ResponseEntity<PlatformReleaseWindowResponseDTO> scheduleReleaseWindow(
        @Valid @RequestBody PlatformReleaseWindowRequestDTO request
    ) {
        PlatformReleaseWindowResponseDTO response = registryService.scheduleReleaseWindow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/registry/snapshot")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Export a snapshot of the platform registry state")
    public ResponseEntity<PlatformRegistrySnapshotDTO> exportSnapshot() {
        return ResponseEntity.ok(registryService.getRegistrySnapshot());
    }
}
