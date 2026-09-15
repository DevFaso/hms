package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PermissionRequestDTO {

    private UUID id;

    @NotBlank(message = "{permission.name.required}")
    private String name;

    @NotNull(message = "{permission.assignmentId.required}")
    private UUID assignmentId;
}
