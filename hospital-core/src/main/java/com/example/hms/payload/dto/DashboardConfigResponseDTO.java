package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * DTO representing dashboard configuration for the authenticated user.
 * Contains roles, permissions, and hospital assignments.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardConfigResponseDTO {

    /**
     * User identifier
     */
    private UUID userId;

    /**
     * Primary role code (e.g., "ROLE_DOCTOR")
     */
    private String primaryRoleCode;

    /**
     * List of all role assignments with their permissions
     */
    private List<RoleAssignmentConfigDTO> roles;

    /**
     * Merged list of all unique permissions across all roles
     */
    private List<String> mergedPermissions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleAssignmentConfigDTO {
        
        /**
         * Role code (e.g., "ROLE_DOCTOR")
         */
        private String roleCode;

        /**
         * Human-readable role name
         */
        private String roleName;

        /**
         * Hospital ID if role is hospital-specific
         */
        private UUID hospitalId;

        /**
         * Hospital name if role is hospital-specific
         */
        private String hospitalName;

        /**
         * List of permissions for this specific role assignment
         */
        private List<String> permissions;
    }
}
