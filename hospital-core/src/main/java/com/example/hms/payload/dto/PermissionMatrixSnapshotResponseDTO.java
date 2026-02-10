package com.example.hms.payload.dto;

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
public class PermissionMatrixSnapshotResponseDTO {

    private UUID id;
    private UUID sourceSnapshotId;
    private PermissionMatrixEnvironment environment;
    private Integer version;
    private String label;
    private String notes;
    private String createdBy;
    private Instant createdAt;
    private List<PermissionMatrixRowDTO> rows;
}