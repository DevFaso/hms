package com.example.hms.payload.dto;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixAuditEventRequestDTO {

    @NotNull(message = "permission.matrix.audit.action.required")
    private PermissionMatrixAuditAction action;

    private PermissionMatrixEnvironment leftEnvironment;

    private PermissionMatrixEnvironment rightEnvironment;

    private UUID snapshotId;

    @Size(max = 512, message = "permission.matrix.audit.description.size")
    private String description;

    private List<@Valid PermissionMatrixRowDTO> matrix;

    @Size(max = 4000, message = "permission.matrix.audit.metadata.size")
    private String metadata;
}