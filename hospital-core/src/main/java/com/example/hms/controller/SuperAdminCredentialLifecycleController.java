package com.example.hms.controller;

import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.service.UserCredentialLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/super-admin/credentials")
@RequiredArgsConstructor
@Tag(name = "Super Admin: Credential Lifecycle", description = "Monitor and govern credential health, MFA enrollments and recovery contacts")
public class SuperAdminCredentialLifecycleController {

    private final UserCredentialLifecycleService userCredentialLifecycleService;

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "List credential health for all active users")
    public ResponseEntity<List<UserCredentialHealthDTO>> listCredentialHealth() {
        return ResponseEntity.ok(userCredentialLifecycleService.listCredentialHealth());
    }

    @GetMapping("/health/{userId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Get credential health for a specific user")
    public ResponseEntity<UserCredentialHealthDTO> getCredentialHealth(@PathVariable UUID userId) {
        return ResponseEntity.ok(userCredentialLifecycleService.getCredentialHealth(userId));
    }

    @PutMapping("/{userId}/mfa")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Upsert MFA enrollments for a user")
    public ResponseEntity<List<UserMfaEnrollmentDTO>> upsertMfaEnrolments(
        @PathVariable UUID userId,
        @Valid @RequestBody List<UserMfaEnrollmentRequestDTO> payload
    ) {
        return ResponseEntity.ok(userCredentialLifecycleService.upsertMfaEnrollments(userId, payload));
    }

    @PutMapping("/{userId}/recovery")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Upsert recovery contacts for a user")
    public ResponseEntity<List<UserRecoveryContactDTO>> upsertRecoveryContacts(
        @PathVariable UUID userId,
        @Valid @RequestBody List<UserRecoveryContactRequestDTO> payload
    ) {
        return ResponseEntity.ok(userCredentialLifecycleService.upsertRecoveryContacts(userId, payload));
    }
}
