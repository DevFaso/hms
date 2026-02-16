package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.SecurityPolicyApprovalSummaryDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineExportDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineResponseDTO;
import com.example.hms.service.SecurityPolicyGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/super-admin/security")
@RequiredArgsConstructor
@Tag(name = "Super Admin Security Policies", description = "Manage cross-tenant security policy baselines and approvals")
@SecurityRequirement(name = "bearerAuth")
public class SuperAdminSecurityPolicyController {

    private final SecurityPolicyGovernanceService securityPolicyGovernanceService;

    @PostMapping("/policies/baselines")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a new security policy baseline")
    public ResponseEntity<SecurityPolicyBaselineResponseDTO> createBaseline(
        @Valid @RequestBody SecurityPolicyBaselineRequestDTO request
    ) {
        SecurityPolicyBaselineResponseDTO response = securityPolicyGovernanceService.createBaseline(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/policies/approvals/pending")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List pending security policy approvals")
    public ResponseEntity<List<SecurityPolicyApprovalSummaryDTO>> listPendingApprovals() {
        return ResponseEntity.ok(securityPolicyGovernanceService.listPendingApprovals());
    }

    @GetMapping("/policies/export/latest")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Export the latest security policy baseline")
    public ResponseEntity<SecurityPolicyBaselineExportDTO> exportLatestBaseline() {
        return ResponseEntity.ok(securityPolicyGovernanceService.exportLatestBaseline());
    }
}
