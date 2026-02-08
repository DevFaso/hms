package com.example.hms.payload.dto.assignment;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "UserRoleAssignmentBulkImportRequestDTO", description = "CSV-driven bulk import of user role assignments")
public class UserRoleAssignmentBulkImportRequestDTO {

    @NotBlank(message = "CSV content must be provided")
    @Schema(description = "Raw CSV content including header row")
    private String csvContent;

    @Size(max = 1)
    @Schema(description = "Delimiter override (defaults to comma)")
    private String delimiter;

    @Schema(description = "Optional default role ID when not provided in CSV")
    private UUID defaultRoleId;

    @Schema(description = "Optional default role name when not provided in CSV")
    private String defaultRoleName;

    @Schema(description = "Optional default hospital ID when not provided in CSV")
    private UUID defaultHospitalId;

    @Schema(description = "Default active flag when not specified per row")
    private Boolean defaultActive;

    @Schema(description = "Registrar user ID applied to imported assignments")
    private UUID registeredByUserId;

    @Builder.Default
    @Schema(description = "Send notifications for imported assignments (emails/SMS)")
    private boolean sendNotifications = false;

    @Builder.Default
    @Schema(description = "Skip conflicting assignments instead of failing the import")
    private boolean skipConflicts = true;
}
