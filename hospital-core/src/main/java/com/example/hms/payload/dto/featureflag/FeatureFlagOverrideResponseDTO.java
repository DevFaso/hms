package com.example.hms.payload.dto.featureflag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One persisted feature-flag override row for the super-admin console
 * listing ({@code GET /feature-flags/overrides}).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagOverrideResponseDTO {

    private UUID id;
    private String flagKey;
    private boolean enabled;
    private String description;
    private String updatedBy;
    /** Null for a global override; the tenant it narrows to otherwise. */
    private UUID organizationId;
    private LocalDateTime updatedAt;
}
