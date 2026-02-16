package com.example.hms.controller;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import com.example.hms.payload.dto.PermissionMatrixAuditEventRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixAuditEventResponseDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotResponseDTO;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.PermissionMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/permissions/matrix")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Permission Matrix Management", description = "Manage the global permission matrix snapshots and audit events")
public class PermissionMatrixController {

    private final PermissionMatrixService permissionMatrixService;

    @PostMapping("/snapshots")
    @Operation(summary = "Publish a new permission matrix snapshot")
    public ResponseEntity<PermissionMatrixSnapshotResponseDTO> publishSnapshot(
        @Valid @RequestBody PermissionMatrixSnapshotRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        String initiatedBy = SecurityUtils.getCurrentUsername();
        PermissionMatrixSnapshotResponseDTO response = permissionMatrixService.publishSnapshot(requestDTO, initiatedBy, locale);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/snapshots/latest")
    @Operation(summary = "Get the latest snapshot for an environment")
    public ResponseEntity<PermissionMatrixSnapshotResponseDTO> getLatestSnapshot(
        @RequestParam PermissionMatrixEnvironment environment,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        return ResponseEntity.ok(permissionMatrixService.getLatestSnapshot(environment, locale));
    }

    @GetMapping("/snapshots")
    @Operation(summary = "List snapshots", description = "Returns snapshots optionally filtered by environment")
    public ResponseEntity<List<PermissionMatrixSnapshotResponseDTO>> listSnapshots(
        @RequestParam(required = false) PermissionMatrixEnvironment environment,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        return ResponseEntity.ok(permissionMatrixService.listSnapshots(environment, locale));
    }

    @GetMapping("/snapshots/{id}")
    @Operation(summary = "Get snapshot by ID")
    public ResponseEntity<PermissionMatrixSnapshotResponseDTO> getSnapshot(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        return ResponseEntity.ok(permissionMatrixService.getSnapshot(id, locale));
    }

    @GetMapping("/environment/{environment}")
    @Operation(summary = "Alias for latest snapshot")
    public ResponseEntity<PermissionMatrixSnapshotResponseDTO> getEnvironmentSnapshot(
        @PathVariable PermissionMatrixEnvironment environment,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale
    ) {
        return ResponseEntity.ok(permissionMatrixService.getLatestSnapshot(environment, locale));
    }

    @PostMapping("/audit")
    @Operation(summary = "Record a matrix audit event")
    public ResponseEntity<PermissionMatrixAuditEventResponseDTO> recordAuditEvent(
        @Valid @RequestBody PermissionMatrixAuditEventRequestDTO requestDTO
    ) {
        String initiatedBy = SecurityUtils.getCurrentUsername();
        return new ResponseEntity<>(permissionMatrixService.recordAuditEvent(requestDTO, initiatedBy), HttpStatus.CREATED);
    }

    @GetMapping("/audit")
    @Operation(summary = "List recent audit events")
    public ResponseEntity<List<PermissionMatrixAuditEventResponseDTO>> listAuditEvents(
        @RequestParam(required = false) PermissionMatrixAuditAction action
    ) {
        return ResponseEntity.ok(permissionMatrixService.listRecentAuditEvents(action));
    }
}