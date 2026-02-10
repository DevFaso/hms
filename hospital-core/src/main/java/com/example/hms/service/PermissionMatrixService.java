package com.example.hms.service;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import com.example.hms.payload.dto.PermissionMatrixAuditEventRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixAuditEventResponseDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotRequestDTO;
import com.example.hms.payload.dto.PermissionMatrixSnapshotResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PermissionMatrixService {

    PermissionMatrixSnapshotResponseDTO publishSnapshot(PermissionMatrixSnapshotRequestDTO request, String initiatedBy, Locale locale);

    PermissionMatrixSnapshotResponseDTO getLatestSnapshot(PermissionMatrixEnvironment environment, Locale locale);

    List<PermissionMatrixSnapshotResponseDTO> listSnapshots(PermissionMatrixEnvironment environment, Locale locale);

    PermissionMatrixSnapshotResponseDTO getSnapshot(UUID snapshotId, Locale locale);

    PermissionMatrixAuditEventResponseDTO recordAuditEvent(PermissionMatrixAuditEventRequestDTO request, String initiatedBy);

    List<PermissionMatrixAuditEventResponseDTO> listRecentAuditEvents(PermissionMatrixAuditAction actionFilter);
}