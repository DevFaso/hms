package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserPasswordRotationDTO;
import com.example.hms.service.UserGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/super-admin/users")
@RequiredArgsConstructor
@Tag(name = "Super Admin: User Governance", description = "Bulk onboarding, CSV import and forced credential workflows")
public class SuperAdminUserGovernanceController {

    private final UserGovernanceService userGovernanceService;

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Create a user under super-admin governance")
    public ResponseEntity<UserResponseDTO> createUser(@Valid @RequestBody AdminSignupRequest request) {
        UserResponseDTO response = userGovernanceService.createUser(request);
        return ResponseEntity.created(URI.create("/super-admin/users/" + response.getId())).body(response);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Import users from CSV")
    public ResponseEntity<SuperAdminUserBulkImportResponseDTO> importUsers(
        @Valid @RequestBody SuperAdminUserBulkImportRequestDTO request
    ) {
        SuperAdminUserBulkImportResponseDTO response = userGovernanceService.importUsers(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/force-password-reset")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Force password reset for selected users")
    public ResponseEntity<SuperAdminUserForcePasswordResetResponseDTO> forcePasswordReset(
        @Valid @RequestBody SuperAdminUserForcePasswordResetRequestDTO request
    ) {
        SuperAdminUserForcePasswordResetResponseDTO response = userGovernanceService.forcePasswordReset(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/password-rotation")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "List users with password rotation status")
    public ResponseEntity<List<SuperAdminUserPasswordRotationDTO>> listPasswordRotationStatus() {
        return ResponseEntity.ok(userGovernanceService.listPasswordRotationStatus());
    }
}
