package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response from POST /super-admin/impersonation/start. The frontend must save the original super-admin token before swapping in this one.")
public class ImpersonationStartResponseDTO {

    @Schema(description = "Short-lived JWT (default 30 min) carrying the target's identity + impersonator claims.")
    private String accessToken;

    @Schema(description = "Epoch instant when the impersonation token expires; the frontend must auto-stop at this time.")
    private Instant expiresAt;

    private UUID impersonatorUserId;
    private String impersonatorUsername;
    private UUID targetUserId;
    private String targetUsername;
}
