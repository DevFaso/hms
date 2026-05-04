package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of a Test connection action (MVP-c batch — MVP-3b).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Connectivity probe outcome.")
public class IntegrationProbeResultDTO {
    private String integrationId;
    private boolean ok;
    private long latencyMs;
    private String message;
}
