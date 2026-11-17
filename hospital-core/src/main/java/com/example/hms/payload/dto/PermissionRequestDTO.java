package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PermissionRequestDTO {

    private UUID id;

    @NotBlank(message = "{permission.name.required}")
    @jakarta.validation.constraints.Size(max = 50)
    private String name;

    /**
     * Canonical code (e.g. PATIENT_READ). QA round 29 sweep: the entity requires
     * it and nothing set it, so every create answered 500 at commit.
     */
    @NotBlank(message = "{permission.code.required}")
    @jakarta.validation.constraints.Size(max = 80)
    private String code;

    @NotNull(message = "{permission.assignmentId.required}")
    private UUID assignmentId;
}
