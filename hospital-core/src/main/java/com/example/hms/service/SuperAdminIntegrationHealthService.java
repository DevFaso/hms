package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;

/**
 * Aggregates the live integration inventory ({@code PlatformIntegrationAdapter}
 * + {@code EligibilityProvider}) with the persisted
 * {@code IntegrationHealthSnapshot} rows for the super-admin Integration
 * Health Console (MVP-3 — see docs/super-admin-gaps.md).
 */
public interface SuperAdminIntegrationHealthService {

    /** Identifier under which all eligibility-provider activity is rolled up. */
    String INTEGRATION_ID_ELIGIBILITY = "eligibility";

    /** Console grid: every integration with per-org snapshot rows + rolled-up counts. */
    IntegrationHealthSummaryDTO getInventory();

    /**
     * Drill-down for a single integration. Throws
     * {@link com.example.hms.exception.ResourceNotFoundException} when the id is unknown.
     */
    IntegrationHealthRowDTO getIntegration(String integrationId);
}
