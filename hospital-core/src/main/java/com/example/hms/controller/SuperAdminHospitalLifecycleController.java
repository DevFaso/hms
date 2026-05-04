package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.HospitalLifecycleResponseDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.service.HospitalLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Super-admin endpoints for the hospital-level lifecycle state machine
 * (MVP-c batch — Hospital lifecycle item).
 *
 * <p>Mirrors the organization-level endpoints exposed by
 * {@code SuperAdminOrganizationController}. Same MFA step-up posture
 * via the {@code X-Mfa-Token} header on destructive actions.
 */
@RestController
@RequestMapping("/super-admin/hospitals")
@RequiredArgsConstructor
@Tag(name = "Super Admin — Hospital Lifecycle",
    description = "Suspend / restore / archive / schedule-purge an individual hospital.")
public class SuperAdminHospitalLifecycleController {

    private final HospitalLifecycleService lifecycleService;

    @GetMapping("/{hospitalId}/lifecycle")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get hospital-lifecycle snapshot",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> getLifecycle(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(lifecycleService.getLifecycle(hospitalId));
    }

    @PostMapping("/{hospitalId}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Suspend a hospital (block all logins at this facility)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> suspend(
        @PathVariable UUID hospitalId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.suspend(hospitalId, request, mfaToken));
    }

    @PostMapping("/{hospitalId}/restore")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Restore a suspended or archived hospital",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> restore(
        @PathVariable UUID hospitalId,
        @Valid @RequestBody(required = false) TenantLifecycleActionRequestDTO request
    ) {
        return ResponseEntity.ok(lifecycleService.restore(hospitalId, request));
    }

    @PostMapping("/{hospitalId}/archive")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Archive a hospital (data retained, hidden by default)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> archive(
        @PathVariable UUID hospitalId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.archive(hospitalId, request, mfaToken));
    }

    @PostMapping("/{hospitalId}/schedule-purge")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Schedule purge for an archived hospital (default 30-day grace)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> schedulePurge(
        @PathVariable UUID hospitalId,
        @Valid @RequestBody TenantLifecycleActionRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        return ResponseEntity.ok(lifecycleService.schedulePurge(hospitalId, request, mfaToken));
    }

    @PostMapping("/{hospitalId}/cancel-purge")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cancel a scheduled hospital purge",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HospitalLifecycleResponseDTO> cancelPurge(
        @PathVariable UUID hospitalId,
        @Valid @RequestBody(required = false) TenantLifecycleActionRequestDTO request
    ) {
        return ResponseEntity.ok(lifecycleService.cancelPurge(hospitalId, request));
    }
}
