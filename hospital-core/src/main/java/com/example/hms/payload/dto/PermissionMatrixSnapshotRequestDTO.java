package com.example.hms.payload.dto;

import com.example.hms.enums.PermissionMatrixEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
public class PermissionMatrixSnapshotRequestDTO {

    private UUID sourceSnapshotId;

    @NotNull(message = "permission.matrix.environment.required")
    private PermissionMatrixEnvironment environment;

    @Size(max = 255, message = "permission.matrix.label.size")
    private String label;

    @Size(max = 2000, message = "permission.matrix.notes.size")
    private String notes;

    @NotEmpty(message = "permission.matrix.rows.required")
    private List<@Valid PermissionMatrixRowDTO> rows;
}