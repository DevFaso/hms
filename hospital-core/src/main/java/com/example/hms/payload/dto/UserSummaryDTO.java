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
 * Lightweight user representation for list / search endpoints.
 * <p>
 * Full details (roles, permissions, timestamps, profile links) are available
 * via {@code GET /api/users/{id}}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserSummaryDTO", description = "Lightweight user payload for list and search results")
public class UserSummaryDTO {

    @Schema(description = "User ID")
    private UUID id;

    @Schema(description = "Unique username (login)")
    private String username;

    @Schema(description = "Primary email")
    private String email;

    @Schema(description = "First name")
    private String firstName;

    @Schema(description = "Last name")
    private String lastName;

    @Schema(description = "Profile image URL")
    private String profileImageUrl;

    @Schema(description = "Whether the user is currently active")
    private boolean active;

    @Schema(description = "Primary role code (e.g., ROLE_SUPER_ADMIN)")
    private String roleName;

    @Schema(description = "Derived profile type: STAFF | PATIENT | ADMIN")
    private String profileType;

    @Schema(description = "Count of active roles across all hospitals")
    private Integer roleCount;
}
