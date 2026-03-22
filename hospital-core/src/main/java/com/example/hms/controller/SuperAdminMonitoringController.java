package com.example.hms.controller;

import com.example.hms.payload.dto.monitoring.SystemAlertDTO;
import com.example.hms.service.SystemAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/super-admin/monitoring")
@RequiredArgsConstructor
@Tag(name = "Super Admin: System Monitoring", description = "Platform-wide alerts and system health monitoring")
public class SuperAdminMonitoringController {

    private final SystemAlertService systemAlertService;

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "List system alerts (paginated)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Page<SystemAlertDTO>> listAlerts(
        @RequestParam(name = "severity", required = false) String severity,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(systemAlertService.listAlerts(severity, pageable));
    }

    @GetMapping("/alerts/summary")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Alert counts grouped by severity", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Map<String, Long>> getAlertSummary() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(systemAlertService.getAlertSummary());
    }

    @PutMapping("/alerts/{id}/acknowledge")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Acknowledge a system alert", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SystemAlertDTO> acknowledgeAlert(
        @PathVariable UUID id,
        Authentication authentication
    ) {
        String acknowledgedBy = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.ok(systemAlertService.acknowledgeAlert(id, acknowledgedBy));
    }

    @PutMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Mark a system alert as resolved", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<SystemAlertDTO> resolveAlert(@PathVariable UUID id) {
        return ResponseEntity.ok(systemAlertService.resolveAlert(id));
    }
}
