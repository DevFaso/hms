package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.EmergencyActionResponseDTO;
import com.example.hms.payload.dto.superadmin.EmergencyBroadcastRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceLogoutRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceMfaRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyKillFeatureRequestDTO;
import com.example.hms.service.EmergencyControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-7: Cross-platform emergency controls. All endpoints require
 * ROLE_SUPER_ADMIN; the frontend additionally requires an MFA step-up
 * (X-Mfa-Token header) before invoking any of these — same pattern as
 * MVP-2 tenant lifecycle and MVP-4 impersonation start.
 */
@RestController
@RequestMapping("/super-admin/emergency")
@RequiredArgsConstructor
@Tag(name = "Super Admin Emergency Controls",
    description = "Force-logout-all / kill-feature / force-MFA-reenrol / broadcast (MVP-7 in docs/super-admin-gaps.md)")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminEmergencyController {

    private final EmergencyControlService service;

    @PostMapping("/force-logout-all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Force-logout every active session by bumping the global min-iat timestamp. Requires X-Mfa-Token when actor has MFA enrolled.")
    public ResponseEntity<EmergencyActionResponseDTO> forceLogoutAll(
        @Valid @RequestBody EmergencyForceLogoutRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(service.forceLogoutAll(request, mfaToken));
    }

    @PostMapping("/kill-feature")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Kill-switch a feature flag platform-wide via FeatureFlagOverride. Requires X-Mfa-Token when actor has MFA enrolled.")
    public ResponseEntity<EmergencyActionResponseDTO> killFeature(
        @Valid @RequestBody EmergencyKillFeatureRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(service.killFeature(request, mfaToken));
    }

    @PostMapping("/force-mfa-reenrol")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Clear MFA enrolment for the listed users, optionally scoped to one hospital. An empty list resets every enrolled user and requires resetAll=true. Requires X-Mfa-Token when actor has MFA enrolled.")
    public ResponseEntity<EmergencyActionResponseDTO> forceMfaReenrol(
        @Valid @RequestBody EmergencyForceMfaRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(service.forceMfaReenrol(request, mfaToken));
    }

    @PostMapping("/broadcast")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Publish a banner message to /topic/emergency-broadcast for every connected client. Requires X-Mfa-Token when actor has MFA enrolled.")
    public ResponseEntity<EmergencyActionResponseDTO> broadcast(
        @Valid @RequestBody EmergencyBroadcastRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(service.broadcast(request, mfaToken));
    }
}
