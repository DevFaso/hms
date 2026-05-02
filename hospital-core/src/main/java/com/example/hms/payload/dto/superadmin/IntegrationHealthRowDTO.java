package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One integration in the console inventory, with its rolled-up worst-case
 * status across all org entries plus the per-org breakdown.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationHealthRowDTO {

    private String integrationId;

    private String displayName;

    /** Null for non-platform integrations (e.g. eligibility providers). */
    private PlatformServiceType serviceType;

    private String provider;

    private boolean enabled;

    @Builder.Default
    private List<String> capabilities = List.of();

    /** Worst-case status across all {@link #organizations} entries. */
    private IntegrationHealthStatus rolledUpStatus;

    @Builder.Default
    private List<IntegrationHealthOrgEntryDTO> organizations = List.of();
}
