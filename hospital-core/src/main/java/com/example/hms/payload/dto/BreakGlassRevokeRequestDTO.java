package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to revoke an active break-the-glass session before its TTL expires.")
public class BreakGlassRevokeRequestDTO {

    @Size(max = 1024)
    @Schema(description = "Optional reason for revoking early (e.g. 'patient regained consciousness, consent obtained').",
            example = "Patient consent now obtained.")
    private String reason;
}
