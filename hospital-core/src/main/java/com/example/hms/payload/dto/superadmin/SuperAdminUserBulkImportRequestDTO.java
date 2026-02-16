package com.example.hms.payload.dto.superadmin;

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
public class SuperAdminUserBulkImportRequestDTO {

    /**
     * Raw CSV data including the header row. Expected columns at minimum:
     * username,email,firstName,lastName,phoneNumber,roles
     */
    @NotBlank(message = "CSV content must be provided.")
    private String csvContent;

    /** Optional default hospital when column is absent. */
    private UUID defaultHospitalId;

    /** Whether imported users should be forced to change password at next login. */
    @Builder.Default
    private boolean forcePasswordChange = true;

    /** Whether to trigger invitation/reset emails for successfully imported users. */
    @Builder.Default
    private boolean sendInviteEmails = true;

    /** Optional delimiter override. Defaults to comma when blank. */
    @Size(max = 1)
    private String delimiter;
}
