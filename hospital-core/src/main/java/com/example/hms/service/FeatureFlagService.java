package com.example.hms.service;

import com.example.hms.payload.dto.featureflag.FeatureFlagOverrideResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public interface FeatureFlagService {

    Map<String, Boolean> listFlags(String environment, Locale locale);

    /**
     * MVP-7b global override path. Equivalent to
     * {@link #upsertOverride(String, boolean, String, String, String, String, UUID, Locale)}
     * with a null {@code organizationId}; kept for legacy callers (e.g.
     * {@code EmergencyControlService.killFeature}).
     */
    Map<String, Boolean> upsertOverride(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environment,
        Locale locale
    );

    /**
     * MVP-7b per-tenant override path. {@code organizationId} null
     * targets the *global* row (same row legacy callers wrote).
     */
    Map<String, Boolean> upsertOverride(
        String flagKey,
        boolean enabled,
        String description,
        String updatedBy,
        String environment,
        UUID organizationId,
        Locale locale
    );

    Map<String, Boolean> deleteOverride(
        String flagKey,
        String updatedBy,
        String environment,
        Locale locale
    );

    /**
     * MVP-7b per-tenant delete path. Drops the per-tenant row when
     * {@code organizationId} is non-null; falls through to the legacy
     * global-row delete when null.
     */
    Map<String, Boolean> deleteOverride(
        String flagKey,
        String updatedBy,
        String environment,
        UUID organizationId,
        Locale locale
    );

    /**
     * All persisted overrides (global and per-tenant) for the super-admin
     * console, ordered by flag key.
     */
    List<FeatureFlagOverrideResponseDTO> listOverrides();
}
