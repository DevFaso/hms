package com.example.hms.controller;

import com.example.hms.payload.dto.superadmin.AuditSavedSearchRequestDTO;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchResponseDTO;
import com.example.hms.service.AuditSavedSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Super-admin REST surface for server-side audit saved searches
 * (MVP-c batch — MVP-8c).
 */
@RestController
@RequestMapping("/super-admin/audit-search/saved")
@RequiredArgsConstructor
@Tag(name = "Super Admin — Audit Saved Searches",
    description = "Persisted / shared audit-search saved queries.")
public class SuperAdminAuditSavedSearchController {

    private final AuditSavedSearchService service;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List the caller's own searches plus shared searches from other super admins",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<AuditSavedSearchResponseDTO>> list() {
        return ResponseEntity.ok(service.listVisible());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a saved search owned by the caller",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AuditSavedSearchResponseDTO> create(
        @Valid @RequestBody AuditSavedSearchRequestDTO request
    ) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update a saved search owned by the caller",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AuditSavedSearchResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody AuditSavedSearchRequestDTO request
    ) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Delete a saved search owned by the caller",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
