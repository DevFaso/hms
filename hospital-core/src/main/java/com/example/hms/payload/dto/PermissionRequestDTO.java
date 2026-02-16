package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PermissionRequestDTO {

    private UUID id;

    @NotBlank(message = "Permission name cannot be blank")
    private String name;

    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;
}
