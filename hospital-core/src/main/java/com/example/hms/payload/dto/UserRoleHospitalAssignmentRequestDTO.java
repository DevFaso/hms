package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserRoleHospitalAssignmentRequestDTO", description = "Create or update a user↔role assignment at a hospital scope")
public class UserRoleHospitalAssignmentRequestDTO {

    @Schema(description = "Client-visible code for the assignment (if you want to set it). Otherwise server can auto-generate.")
    private String assignmentCode;

    @Schema(description = "User UUID to assign (optional if userIdentifier provided)")
    private UUID userId;

    @Size(max = 150)
    @Schema(description = "Human identifier: username OR email OR phone; used when userId not supplied")
    private String userIdentifier;

    @Schema(description = "Hospital scope. Optional only for global/system roles; required for hospital-scoped roles.")
    private UUID hospitalId;

    @Size(max = 120)
    @Schema(description = "Hospital code (case-insensitive) – alternative to hospitalId")
    private String hospitalCode;

    @Size(max = 255)
    @Schema(description = "Hospital name (case-insensitive) – alternative if code absent")
    private String hospitalName;

    @Schema(description = "Provide exactly one of roleId or roleName")
    private UUID roleId;

    @Size(max = 100)
    @Schema(description = "Provide exactly one of roleId or roleName, e.g. ROLE_HOSPITAL_ADMIN or HOSPITAL_ADMIN")
    private String roleName;

    // Do NOT rely on Lombok default with Jackson; handle defaulting in service:
    @Schema(description = "Whether this assignment is active (default true if null)")
    private Boolean active;

    @Schema(description = "User who is registering this assignment (auditing)")
    private UUID registeredByUserId;

    @Schema(description = "Assignment start date (inclusive)")
    private LocalDate startDate;

    /** Bean Validation: exactly one of roleId or roleName must be present */
    @AssertTrue(message = "Provide exactly one of roleId or roleName.")
    public boolean isExactlyOneRoleIdentifierPresent() {
        boolean hasId = roleId != null;
        boolean hasName = roleName != null && !roleName.isBlank();
        return hasId ^ hasName;
    }

    @AssertTrue(message = "Provide userId or userIdentifier")
    public boolean isUserIdentifierValid() {
        return userId != null || (userIdentifier != null && !userIdentifier.isBlank());
    }

    @AssertTrue(message = "Provide hospitalId or (hospitalCode/hospitalName) for non-global roles")
    public boolean isHospitalIdentifierValid() {
    // Relaxed: allow omission entirely; service layer will enforce requirement based on role.
    return true;
    }
}
