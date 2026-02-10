package com.example.hms.controller;

import com.example.hms.payload.dto.RoleBlueprintDTO;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;
import com.example.hms.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "List roles")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<List<RoleResponseDTO>> list() {
        return ResponseEntity.ok(roleService.list());
    }

    @GetMapping("/blueprints")
    @Operation(summary = "List available role blueprints")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<RoleBlueprintDTO>> blueprints() {
        return ResponseEntity.ok(roleService.listBlueprints());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role by id")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<RoleResponseDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(roleService.getById(id));
    }

    @GetMapping("/matrix/export")
    @Operation(summary = "Export the role-permission matrix as CSV")
    @ApiResponse(responseCode = "200")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ByteArrayResource> exportMatrix() {
        byte[] payload = roleService.exportMatrixCsv();
        ByteArrayResource resource = new ByteArrayResource(payload);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=role-permission-matrix.csv")
            .contentLength(payload.length)
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(resource);
    }

    @PostMapping
    @Operation(summary = "Create role")
    @ApiResponse(responseCode = "201")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<RoleResponseDTO> create(@Valid @RequestBody RoleRequestDTO request) {
        return new ResponseEntity<>(roleService.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<RoleResponseDTO> update(@PathVariable UUID id, @Valid @RequestBody RoleRequestDTO request) {
        return ResponseEntity.ok(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permissions")
    @Operation(summary = "Replace permissions for a role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<RoleResponseDTO> setPermissions(@PathVariable UUID id, @RequestBody Set<UUID> permissionIds) {
        return ResponseEntity.ok(roleService.assignPermissions(id, permissionIds));
    }
}
