package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.OrganizationRegion;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Snapshot of one region's policy overrides (MVP-c batch — MVP-9c).
 * Null fields signify "fall back to global policy".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-region policy overrides for retention / export-format / deployment routing.")
public class RegionPolicyResponseDTO {

    private OrganizationRegion region;
    private Integer retentionDays;
    private String defaultExportFormat;
    private String targetDeploymentUrl;
    private Instant updatedAt;
    private String updatedBy;
}
