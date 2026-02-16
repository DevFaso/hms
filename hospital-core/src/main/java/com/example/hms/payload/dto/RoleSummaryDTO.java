package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Slim role representation embedded in user detail responses.
 * <p>
 * Permissions are available separately via
 * {@code GET /api/me/dashboard-config} (for the current user) or the
 * role management endpoints {@code GET /api/roles/{id}}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "RoleSummaryDTO", description = "Lightweight role payload for user detail context")
public class RoleSummaryDTO {

    @Schema(description = "Role ID")
    private UUID id;

    @Schema(description = "Machine-readable role code (e.g., ROLE_SUPER_ADMIN)")
    private String code;

    @Schema(description = "Human-readable role name")
    private String name;

    @Schema(description = "Role description")
    private String description;
}
