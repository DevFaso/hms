package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;
import com.example.hms.service.SupportImpersonationService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/super-admin/impersonation")
@RequiredArgsConstructor
@Tag(name = "Super Admin Support Impersonation",
    description = "Mint a short-lived JWT representing another user so support can act as them with full audit (MVP-4).")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminImpersonationController {

    private final SupportImpersonationService service;

    @PostMapping("/start")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Start a support-impersonation session. Requires X-Mfa-Token when actor has MFA enrolled.")
    public ResponseEntity<ImpersonationStartResponseDTO> start(
        @Valid @RequestBody ImpersonationStartRequestDTO request,
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken
    ) {
        ImpersonationStartResponseDTO response = service.start(request, mfaToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/stop")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "End the current support-impersonation session and emit IMPERSONATION_ENDED. The bearer token at this call is the impersonation token; the frontend then restores the saved super-admin token.")
    public ResponseEntity<ImpersonationActiveResponseDTO> stop() {
        return ResponseEntity.ok(service.stop());
    }

    @GetMapping("/active")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Whether the current request is running under an impersonation token.")
    public ResponseEntity<ImpersonationActiveResponseDTO> active() {
        return ResponseEntity.ok(service.getActive());
    }
}
