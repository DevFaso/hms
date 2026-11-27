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

    /**
     * QA D851: whether the inventory lists {@code integrationId}, so the
     * history and re-sync endpoints know every integration the list shows,
     * not only the ones with a probe or a re-sync bean.
     */
    boolean isListed(String integrationId);

    /**
     * QA D4300: the test of a listed integration that has no connectivity probe
     * of its own - whether it is configured and switched on. Empty for an
     * integration this service does not list.
     */
    java.util.Optional<com.example.hms.service.integration.probe.Probe> configurationCheck(String integrationId);
}
