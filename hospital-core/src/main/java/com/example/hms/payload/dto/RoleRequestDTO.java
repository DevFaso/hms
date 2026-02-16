package com.example.hms.payload.dto;

import com.example.hms.enums.RoleBlueprint;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(name = "RoleRequestDTO", description = "Payload to create or update a role")
public class RoleRequestDTO {

    private UUID id;

    @NotBlank(message = "Role name cannot be blank")
    @Size(min = 2, max = 50)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Name of the role, e.g. HOSPITAL_ADMIN")
    private String name;

    @NotBlank(message = "Role authority cannot be blank")
    @Size(min = 2, max = 50)
    @Schema(description = "Authority code for the role, typically ROLE_*", example = "ROLE_OPERATIONS_ADMIN")
    private String code;

    @Size(max = 255)
    @Schema(description = "Optional human-readable description of the role", example = "Responsible for day-to-day hospital operations")
    private String description;

    @Schema(description = "IDs of permissions attached to this role")
    private Set<UUID> permissionIds;

    @Schema(description = "Optional role ID to use as a template when creating or updating permissions")
    private UUID templateRoleId;

    @Schema(description = "Optional blueprint to bootstrap default permissions")
    private RoleBlueprint blueprint;
}
