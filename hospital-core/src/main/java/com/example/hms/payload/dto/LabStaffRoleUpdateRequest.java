package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request to change a lab staff member's role.
 * Only lab roles are allowed: ROLE_LAB_TECHNICIAN, ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabStaffRoleUpdateRequest {

    @NotBlank(message = "roleCode is required")
    @Pattern(
        regexp = "ROLE_LAB_TECHNICIAN|ROLE_LAB_SCIENTIST|ROLE_LAB_MANAGER",
        message = "roleCode must be one of: ROLE_LAB_TECHNICIAN, ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER"
    )
    private String roleCode;
}
