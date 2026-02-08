package com.example.hms.payload.dto.assignment;

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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserRoleAssignmentMultiRequestDTO", description = "Create the same user↔role assignment across multiple hospital or organization scopes")
public class UserRoleAssignmentMultiRequestDTO {

    @Schema(description = "Target user identifier (UUID)")
    private UUID userId;

    @Schema(description = "Alternative user identifier (username/email/phone)")
    private String userIdentifier;

    @Schema(description = "Role identifier (UUID). Provide exactly one of roleId or roleName")
    private UUID roleId;

    @Size(max = 120)
    @Schema(description = "Role code/name (case-insensitive). Provide exactly one of roleId or roleName")
    private String roleName;

    @Builder.Default
    @Schema(description = "Explicit hospital targets. Duplicates are ignored")
    private List<UUID> hospitalIds = Collections.emptyList();

    @Builder.Default
    @Schema(description = "Organization scopes. All active hospitals for these organizations will be targeted")
    private List<UUID> organizationIds = Collections.emptyList();

    @Schema(description = "Override assignment active flag for created records")
    private Boolean active;

    @Schema(description = "Optional start date for created assignments")
    private LocalDate startDate;

    @Schema(description = "Registrar user ID (defaults to authenticated principal when absent)")
    private UUID registeredByUserId;

    @Builder.Default
    @Schema(description = "Whether notification emails/SMS should be sent for each created assignment")
    private boolean sendNotifications = true;

    @Builder.Default
    @Schema(description = "Skip conflicting assignments instead of failing the entire batch")
    private boolean skipConflicts = true;

    @AssertTrue(message = "Provide either userId or userIdentifier")
    public boolean isUserProvided() {
        return userId != null || (userIdentifier != null && !userIdentifier.isBlank());
    }

    @AssertTrue(message = "Provide exactly one of roleId or roleName")
    public boolean isValidRoleIdentifier() {
        boolean hasId = roleId != null;
        boolean hasName = roleName != null && !roleName.isBlank();
        return hasId ^ hasName;
    }
}
