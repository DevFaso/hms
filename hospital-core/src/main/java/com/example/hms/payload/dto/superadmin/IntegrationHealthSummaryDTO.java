package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response for {@code GET /super-admin/integrations}. Provides
 * counts for the control-tower tile plus the full inventory grid.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationHealthSummaryDTO {

    private int totalIntegrations;

    private int healthyCount;

    private int degradedCount;

    private int failingCount;

    private int noHistoryCount;

    @Builder.Default
    private List<IntegrationHealthRowDTO> integrations = List.of();
}
