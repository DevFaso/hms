package com.example.hms.payload.dto;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixAuditEventResponseDTO {

    private UUID id;
    private PermissionMatrixAuditAction action;
    private PermissionMatrixEnvironment leftEnvironment;
    private PermissionMatrixEnvironment rightEnvironment;
    private UUID snapshotId;
    private String description;
    private String initiatedBy;
    private Instant createdAt;
    private List<PermissionMatrixRowDTO> matrix;
    private String metadata;
}