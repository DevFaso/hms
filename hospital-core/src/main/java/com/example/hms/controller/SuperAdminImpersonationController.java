package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;
import com.example.hms.service.SupportImpersonationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

import static com.example.hms.config.SecurityConstants.HEADER_STRING;
import static com.example.hms.config.SecurityConstants.TOKEN_PREFIX;

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
        @RequestHeader(value = "X-Mfa-Token", required = false) String mfaToken,
        HttpServletRequest httpRequest
    ) {
        // Closes Copilot review #2 + #4 on PR #224 — the service blacklists
        // this token (so a remembered-session client cannot keep using it
        // from localStorage) and registers an active session in the
        // ImpersonationSessionTracker (so /auth/token/refresh refuses to
        // mint a fresh super-admin access token until stop() is called).
        String bearerJwt = extractBearer(httpRequest);
        ImpersonationStartResponseDTO response = service.start(request, mfaToken, bearerJwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/stop")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "End the current support-impersonation session and emit IMPERSONATION_ENDED. The bearer token at this call is the impersonation token and is blacklisted by the service so a copy cannot continue authenticating.")
    public ResponseEntity<ImpersonationActiveResponseDTO> stop(HttpServletRequest httpRequest) {
        String bearerJwt = extractBearer(httpRequest);
        return ResponseEntity.ok(service.stop(bearerJwt));
    }

    @GetMapping("/active")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Whether the current request is running under an impersonation token.")
    public ResponseEntity<ImpersonationActiveResponseDTO> active() {
        return ResponseEntity.ok(service.getActive());
    }

    private static String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HEADER_STRING);
        if (header == null || !header.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        return header.substring(TOKEN_PREFIX.length());
    }
}
