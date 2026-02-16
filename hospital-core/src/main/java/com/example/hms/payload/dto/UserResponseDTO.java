package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserResponseDTO", description = "Hydrated user payload with roles and profile pointers")
public class UserResponseDTO {

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

    @Schema(description = "E.164 phone number")
    private String phoneNumber;

    @Schema(description = "Profile image URL")
    private String profileImageUrl;

    @Schema(description = "Whether the user currently has at least one active assignment")
    private boolean active;

    @Schema(description = "Last login timestamp (server local time)")
    private LocalDateTime lastLoginAt;

    @Schema(description = "Creation timestamp (server local time)")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp (server local time)")
    private LocalDateTime updatedAt;

    @Builder.Default
    @Schema(description = "Assigned roles (id, code, name, description — no permissions; use GET /api/me/dashboard-config for permissions)")
    private Set<RoleSummaryDTO> roles = new LinkedHashSet<>();

    @Schema(description = "Derived profile type: STAFF | PATIENT | ADMIN, etc.")
    private String profileType;

    @Schema(description = "License number when profileType = STAFF")
    private String licenseNumber;

    @Schema(description = "Primary role code/name (e.g., ROLE_HOSPITAL_ADMIN)")
    private String roleName;

    @Schema(description = "Linked patient profile ID when applicable")
    private UUID patientId;

    @Schema(description = "Linked staff profile ID when applicable")
    private UUID staffId;

    @Schema(description = "Count of active roles across all hospitals")
    private Integer roleCount;

    @Schema(description = "If true, user must reset password at next login")
    private Boolean forcePasswordChange;

}
