package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update payload for one region's policy (MVP-c batch — MVP-9c).
 * All fields are optional; null clears the override (falls back to
 * global policy).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Update region-policy overrides; null fields clear the override.")
public class RegionPolicyUpdateRequestDTO {

    @PositiveOrZero
    @Schema(description = "Retention override in days. Null clears.")
    private Integer retentionDays;

    @Size(max = 32)
    @Schema(description = "Export-format override (e.g. STANDARD, GDPR_PORTABILITY). Null clears.")
    private String defaultExportFormat;

    @Size(max = 255)
    @Schema(description = "Target deployment URL for region routing. Null clears (provision locally).")
    private String targetDeploymentUrl;
}
