package com.example.hms.controller;

import com.example.hms.payload.dto.PermissionFilterDTO;
import com.example.hms.payload.dto.PermissionMinimalDTO;
import com.example.hms.payload.dto.PermissionRequestDTO;
import com.example.hms.payload.dto.PermissionResponseDTO;
import com.example.hms.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/permissions")
@Tag(name = "Permission Management", description = "APIs for managing system permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final MessageSource messageSource;

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create Permission", description = "Creates a new system permission.")
    public ResponseEntity<PermissionResponseDTO> createPermission(
        @Valid @RequestBody PermissionRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        PermissionResponseDTO created = permissionService.createPermission(requestDTO, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get Permission by ID", description = "Fetches a permission by its ID.")
    public ResponseEntity<PermissionResponseDTO> getPermissionById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(permissionService.getPermissionById(id, locale));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get All Permissions", description = "Retrieves all system permissions.")
    public ResponseEntity<List<PermissionResponseDTO>> getAllPermissions(
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(permissionService.getAllPermissions(locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update Permission", description = "Updates an existing permission.")
    public ResponseEntity<PermissionResponseDTO> updatePermission(
        @PathVariable UUID id,
        @Valid @RequestBody PermissionRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        return ResponseEntity.ok(permissionService.updatePermission(id, requestDTO, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Delete Permission", description = "Deletes a permission by its ID.")
    public ResponseEntity<String> deletePermission(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        permissionService.deletePermission(id, locale);
        String message = messageSource.getMessage("permission.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(message);
    }

    @GetMapping("/minimal")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Get Minimal Permissions", description = "Returns only ID and name for dropdowns.")
    public ResponseEntity<List<PermissionMinimalDTO>> getMinimalPermissions() {
        return ResponseEntity.ok(permissionService.getMinimalPermissions());
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Search Permissions with filters", description = "Supports filtering and pagination.")
    public ResponseEntity<Page<PermissionResponseDTO>> searchPermissions(
        @RequestParam(required = false) UUID assignmentId,
        @RequestParam(required = false) String name,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {
        PermissionFilterDTO filter = new PermissionFilterDTO(assignmentId, name);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(permissionService.getPermissions(filter, pageable, locale));
    }
}
