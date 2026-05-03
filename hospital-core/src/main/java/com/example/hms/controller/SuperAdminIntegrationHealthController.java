package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;
import com.example.hms.service.SuperAdminIntegrationHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/super-admin/integrations")
@RequiredArgsConstructor
@Tag(name = "Super Admin Integration Health",
    description = "Cross-tenant inventory and health for partner connectors and platform integrations (MVP-3 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminIntegrationHealthController {

    private final SuperAdminIntegrationHealthService service;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Inventory of every integration with rolled-up health and per-org snapshot rows")
    public ResponseEntity<IntegrationHealthSummaryDTO> getInventory() {
        return ResponseEntity.ok(service.getInventory());
    }

    @GetMapping("/{integrationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Drill-down for one integration: rolled-up status plus per-org snapshot history")
    public ResponseEntity<IntegrationHealthRowDTO> getIntegration(@PathVariable String integrationId) {
        return ResponseEntity.ok(service.getIntegration(integrationId));
    }
}
