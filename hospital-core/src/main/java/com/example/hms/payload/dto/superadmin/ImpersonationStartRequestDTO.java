package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to start a support-impersonation session (MVP-4). The X-Mfa-Token header is required when the actor has MFA enrolled.")
public class ImpersonationStartRequestDTO {

    @NotNull
    @Schema(description = "Id of the user the super admin wants to act as.", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID targetUserId;

    @NotBlank
    @Size(min = 5, max = 500)
    @Schema(description = "Free-text justification for the impersonation; persisted in the IMPERSONATION_STARTED audit event.", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
