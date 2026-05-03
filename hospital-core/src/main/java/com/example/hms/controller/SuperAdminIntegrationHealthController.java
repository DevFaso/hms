package com.example.hms.controller;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHistoryBucketDTO;
import com.example.hms.payload.dto.superadmin.IntegrationProbeResultDTO;
import com.example.hms.service.SuperAdminIntegrationHealthService;
import com.example.hms.service.integration.health.IntegrationHealthActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/super-admin/integrations")
@RequiredArgsConstructor
@Tag(name = "Super Admin Integration Health",
    description = "Cross-tenant inventory and health for partner connectors and platform integrations (MVP-3 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminIntegrationHealthController {

    private final SuperAdminIntegrationHealthService service;
    private final IntegrationHealthActionService actionService;

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

    @PostMapping("/{integrationId}/probe")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "MVP-3b: Test connection — runs the registered probe and records the outcome")
    public ResponseEntity<IntegrationProbeResultDTO> probe(
        @PathVariable String integrationId,
        @RequestParam(value = "organizationId", required = false) UUID organizationId
    ) {
        return ResponseEntity.ok(actionService.testConnection(integrationId, organizationId));
    }

    @PostMapping("/{integrationId}/resync")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "MVP-3b: Re-sync — dispatches an asynchronous re-sync; returns 422 if not supported")
    public ResponseEntity<Void> resync(
        @PathVariable String integrationId,
        @RequestParam(value = "organizationId", required = false) UUID organizationId
    ) {
        if (!actionService.isKnownIntegration(integrationId)) {
            throw new ResourceNotFoundException("Unknown integration: " + integrationId);
        }
        if (!actionService.supportsResync(integrationId)) {
            throw new BusinessRuleException(
                "Integration " + integrationId + " does not support re-sync.");
        }
        actionService.resync(integrationId, organizationId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{integrationId}/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "MVP-3b: 24h bucketed success/failure history for the sparkline")
    public ResponseEntity<List<IntegrationHistoryBucketDTO>> getHistory(
        @PathVariable String integrationId,
        @RequestParam(value = "windowHours", defaultValue = "24") int windowHours
    ) {
        return ResponseEntity.ok(actionService.getHistory(integrationId, windowHours));
    }
}
